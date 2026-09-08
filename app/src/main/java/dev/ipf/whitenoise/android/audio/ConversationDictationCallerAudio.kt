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
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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

/**
 * Captures dictation audio in White Noise and streams it to the speech provider through
 * [android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE].
 *
 * A provider Android has not selected in `voice_recognition_service` is bound without
 * `BIND_INCLUDE_CAPABILITIES`, so it never holds the while-in-use microphone capability that an
 * "only while using the app" RECORD_AUDIO grant needs, and `RecognitionService` fails the session
 * with `ERROR_INSUFFICIENT_PERMISSIONS` before the provider records anything. White Noise is the
 * app the user is looking at and runs a microphone-typed foreground service, so it holds that
 * capability itself. Capturing here and handing the provider a descriptor moves the microphone to
 * the side of the boundary that is allowed to open it.
 *
 * Closing the write end ends the utterance, so the provider sees the same end of audio it would
 * get from its own recorder stopping.
 */
internal class ConversationDictationCallerAudio private constructor(
    private val recorder: AudioRecord,
    private val writeEnd: ParcelFileDescriptor,
    /** The descriptor handed to the provider through the recognizer intent. */
    val providerEnd: ParcelFileDescriptor,
) {
    private val streaming = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    /** Begins capture and starts streaming to the provider. Reports whether capture is running. */
    fun start(): Boolean {
        if (!streaming.compareAndSet(false, true)) return false

        val recording = beginRecording()
        if (recording) {
            conversationDictationDiagnostic(
                "event=caller_audio_started sample_rate=$CALLER_AUDIO_SAMPLE_RATE_HZ " +
                    "channels=$CALLER_AUDIO_CHANNEL_COUNT encoding=pcm16",
            )
            thread(name = "dictation-caller-audio", isDaemon = true) { stream() }
        } else {
            conversationDictationDiagnostic(
                "event=caller_audio_start_failed recording_state=${recorder.recordingState}",
            )
            streaming.set(false)
            release()
        }
        return recording
    }

    /**
     * Ends the utterance by closing the audio, which is how a provider reading a caller descriptor
     * learns the user stopped speaking.
     */
    fun stop() = finish("stop")

    /** Abandons capture without waiting for a result. */
    fun cancel() = finish("cancel")

    private fun beginRecording(): Boolean =
        runCatching { recorder.startRecording() }.isSuccess &&
            recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING

    private fun finish(reason: String) {
        if (!finished.compareAndSet(false, true)) return
        conversationDictationDiagnostic("event=caller_audio_finish reason=$reason")
        // The streaming thread owns the recorder and both descriptors once it is running, so it
        // does the teardown; clearing the flag is what stops it.
        if (!streaming.compareAndSet(true, false)) release()
    }

    /** Closes the capture side without streaming, for a session that never started. */
    private fun release() {
        runCatching(recorder::release)
        runCatching(writeEnd::close)
        runCatching(providerEnd::close)
    }

    private fun stream() {
        val samples = ShortArray(FRAMES_PER_READ)
        val encoded = ByteArray(FRAMES_PER_READ * BYTES_PER_FRAME)
        val progress = CallerAudioProgress()
        val sink = writeEnd.fileDescriptor

        try {
            while (streaming.get() && progress.stopReason == null) {
                val read = recorder.read(samples, 0, samples.size)
                if (read <= 0) {
                    progress.stopReason = "read=$read"
                } else {
                    writeFrames(sink, samples, encoded, read, progress)
                }
            }
        } finally {
            runCatching(recorder::stop)
            runCatching(recorder::release)
            // Closing the write end is what tells a provider reading the descriptor that the
            // utterance ended, so it happens before this side's read-end copy goes away.
            runCatching(writeEnd::close)
            runCatching(providerEnd::close)
            progress.reportClosed()
        }
    }

    /**
     * Writes one read to the non-blocking pipe, dropping what does not fit.
     *
     * A provider that received the descriptor and is not draining it has already lost the
     * utterance, so audio is dropped and counted instead of blocking here. Blocking would strand
     * this thread inside `write` where it can no longer see [streaming], which would leave the
     * microphone open after Cancel or Send.
     */
    private fun writeFrames(
        sink: FileDescriptor,
        samples: ShortArray,
        encoded: ByteArray,
        frames: Int,
        progress: CallerAudioProgress,
    ) {
        conversationDictationEncodePcm16(samples, frames, encoded)
        val total = frames * BYTES_PER_FRAME
        var written = 0
        var accepted = 1
        while (accepted > 0 && written < total) {
            accepted = writeChunk(sink, encoded, written, total - written, progress)
            written += accepted
        }
        if (progress.stopReason == null) {
            progress.record(frames, conversationDictationPeak(samples, frames))
        }
    }

    /**
     * Writes what the pipe will take right now. Reports the byte count accepted, or zero once the
     * pipe is full or the write failed, having recorded which of the two happened.
     */
    private fun writeChunk(
        sink: FileDescriptor,
        encoded: ByteArray,
        offset: Int,
        length: Int,
        progress: CallerAudioProgress,
    ): Int =
        try {
            val accepted = Os.write(sink, encoded, offset, length)
            if (accepted <= 0) progress.stopReason = "write=$accepted"
            accepted.coerceAtLeast(0)
        } catch (errno: ErrnoException) {
            if (errno.errno == OsConstants.EAGAIN) {
                progress.drop(length)
            } else {
                progress.stopReason = "write_failed=${OsConstants.errnoName(errno.errno)}"
            }
            0
        }

    companion object {
        /** Opens a capture pipe, or reports null after logging why the microphone stayed closed. */
        fun open(): ConversationDictationCallerAudio? {
            val pipe = openPipe() ?: return null
            val recorder = openRecorder()
            return if (recorder == null) {
                pipe.forEach { runCatching(it::close) }
                null
            } else {
                ConversationDictationCallerAudio(
                    recorder = recorder,
                    writeEnd = pipe[1],
                    providerEnd = pipe[0],
                )
            }
        }

        private fun openPipe(): Array<ParcelFileDescriptor>? {
            val pipe = runCatching { ParcelFileDescriptor.createPipe() }.reportPipeFailure() ?: return null
            return runCatching { markWriteEndNonBlocking(pipe[1]) }
                .onFailure { pipe.forEach { end -> runCatching(end::close) } }
                .map { pipe }
                .reportPipeFailure()
        }

        private fun <T> Result<T>.reportPipeFailure(): T? =
            onFailure {
                conversationDictationDiagnostic(
                    "event=caller_audio_pipe_failed type=${it.javaClass.simpleName}",
                )
            }.getOrNull()

        /**
         * Makes the capture side of the pipe refuse rather than wait once it is full, so a provider
         * that stops reading cannot hold the capture thread, and with it the microphone, open.
         */
        private fun markWriteEndNonBlocking(writeEnd: ParcelFileDescriptor) {
            val current = Os.fcntlInt(writeEnd.fileDescriptor, OsConstants.F_GETFL, 0)
            Os.fcntlInt(
                writeEnd.fileDescriptor,
                OsConstants.F_SETFL,
                current or OsConstants.O_NONBLOCK,
            )
        }

        private fun openRecorder(): AudioRecord? {
            val recorder = runCatching(::buildRecorder).getOrNull()
            val ready = recorder?.state == AudioRecord.STATE_INITIALIZED
            if (!ready) {
                conversationDictationDiagnostic(
                    "event=caller_audio_open_failed state=${recorder?.state ?: "none"}",
                )
                recorder?.let { runCatching(it::release) }
            }
            return recorder?.takeIf { ready }
        }

        @SuppressLint("MissingPermission") // The session checks the grant before creating a source.
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

/**
 * Running totals for one capture, reported at a fixed cadence so a log shows whether White Noise
 * heard anything without recording what was said.
 */
private class CallerAudioProgress {
    private val startedAt = SystemClock.elapsedRealtime()
    private var lastReport = startedAt

    /** Audio the microphone produced, whether or not the provider took it. */
    private var totalBytes = 0L

    /** The part of [totalBytes] the provider would not take, so a stall reads as one. */
    private var droppedBytes = 0L
    private var intervalPeak = 0f
    private var speechReported = false

    /** Set once the capture loop should end, and named in the closing event. */
    var stopReason: String? = null

    fun record(
        frames: Int,
        peak: Float,
    ) {
        totalBytes += frames.toLong() * BYTES_PER_FRAME
        intervalPeak = max(intervalPeak, peak)
        reportFirstSpeech()
        reportInterval()
    }

    /** Counts audio the provider was not reading fast enough to take. */
    fun drop(bytes: Int) {
        droppedBytes += bytes.toLong()
    }

    fun reportClosed() {
        conversationDictationDiagnostic(
            "event=caller_audio_closed reason=${stopReason ?: "stopped"} bytes=$totalBytes " +
                "dropped=$droppedBytes ms=${elapsed()} heard_speech=$speechReported",
        )
    }

    private fun reportFirstSpeech() {
        if (speechReported || intervalPeak < SPEECH_PEAK) return
        speechReported = true
        conversationDictationDiagnostic(
            "event=caller_audio_speech_detected ms=${elapsed()} peak=${format(intervalPeak)}",
        )
    }

    private fun reportInterval() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReport < PROGRESS_INTERVAL_MILLIS) return
        lastReport = now
        conversationDictationDiagnostic(
            "event=caller_audio_progress ms=${elapsed()} bytes=$totalBytes " +
                "dropped=$droppedBytes peak=${format(intervalPeak)}",
        )
        intervalPeak = 0f
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

/** Loudest sample in the range, normalised to 0..1, so a log can separate silence from speech. */
internal fun conversationDictationPeak(
    samples: ShortArray,
    count: Int,
): Float {
    var peak = 0
    for (index in 0 until count) {
        peak = max(peak, abs(samples[index].toInt()))
    }
    return peak / SHORT_FULL_SCALE
}
