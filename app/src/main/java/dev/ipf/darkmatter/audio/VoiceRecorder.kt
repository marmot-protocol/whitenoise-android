package dev.ipf.darkmatter.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Voice-message capture wrapper around `MediaRecorder`.
 *
 * - Encodes mono AAC-LC in an MP4 (`.m4a`) container at 16 kHz / 32 kbps.
 *   That bitrate yields ~4 KB/s, comfortably below the album-byte cap for
 *   anything short of a multi-minute monologue, and AAC-LC is decodable by
 *   the receive-side `MediaPlayer` without any extra dependency.
 * - One recorder per outgoing message — instantiate, [start], then either
 *   [stop] (commit + return the file with duration) or [cancel] (drop the
 *   file and release the recorder).
 *
 * NOT thread-safe. Call from a single owner — the composer's coroutine.
 */
class VoiceRecorder(
    private val context: Context,
    private val outputFile: File,
) {
    private var recorder: MediaRecorder? = null
    private var startedAtNanos: Long = 0L

    val isRecording: Boolean get() = recorder != null

    /**
     * Begin capture. Throws if the underlying `MediaRecorder` can't be
     * primed (typically a missing permission or a busy mic) — the caller
     * surfaces a toast and skips the send.
     */
    fun start() {
        require(recorder == null) { "VoiceRecorder.start called twice" }
        outputFile.parentFile?.mkdirs()
        // API 31+ has a context-aware ctor that lets the platform attribute
        // mic usage to the app for the privacy indicator. Older releases
        // get the legacy ctor.
        val r =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(SAMPLE_RATE_HZ)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(BITRATE_BPS)
            r.setOutputFile(outputFile.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            startedAtNanos = System.nanoTime()
        } catch (t: Throwable) {
            // The MediaRecorder state machine is unforgiving: a failed
            // prepare/start leaves the instance unusable, so release it and
            // wipe any partial file rather than handing the caller a stub.
            runCatching { r.release() }
            runCatching { outputFile.delete() }
            throw t
        }
    }

    /**
     * Stop the recording and return the captured payload. Returns null when
     * the underlying `MediaRecorder.stop()` failed (typically a record that
     * was too short for the encoder to finalize the MP4 atom — the file is
     * unusable). The recorder is released either way; safe to discard the
     * instance after.
     */
    fun stop(): Result? {
        val r = recorder ?: return null
        recorder = null
        return try {
            r.stop()
            val durationMs = ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
            r.release()
            if (outputFile.length() <= 0L) {
                runCatching { outputFile.delete() }
                null
            } else {
                Result(outputFile, durationMs)
            }
        } catch (t: Throwable) {
            // MediaRecorder.stop() throws RuntimeException when stop is
            // called before any audio was captured (the encoder hasn't
            // emitted a valid atom yet). Drop the partial file and signal
            // failure to the caller without crashing the composer.
            Log.w("VoiceRecorder", "stop() failed; discarding partial recording", t)
            runCatching { r.release() }
            runCatching { outputFile.delete() }
            null
        }
    }

    /**
     * Abort the recording and discard the file. Safe to call at any time —
     * a no-op when not recording.
     */
    fun cancel() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        runCatching { r.release() }
        runCatching { outputFile.delete() }
    }

    data class Result(
        val file: File,
        val durationMs: Long,
    )

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val BITRATE_BPS = 32_000
        const val MIME_TYPE = "audio/mp4"
        const val FILE_EXTENSION = "m4a"
    }
}
