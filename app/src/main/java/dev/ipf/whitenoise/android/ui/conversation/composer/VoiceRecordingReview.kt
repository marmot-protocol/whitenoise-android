package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** One finalized native recording, owned exclusively by its conversation's review surface. */
internal data class VoiceReviewClip(
    val file: File,
    val durationMs: Long,
    val key: String = "review:${UUID.randomUUID()}",
)

/** Native playback seam: tests can exercise disposal without acquiring audio focus or opening a player. */
internal interface VoiceReviewPlayback {
    /** Prepares and plays this private take through the app audio owner. */
    suspend fun play(clip: VoiceReviewClip): Boolean

    /** Pauses only the named take, preserving playback position. */
    fun pause(key: String)

    /** Stops only the named take without interrupting a different conversation. */
    fun stop(key: String)

    /** Reports playback ownership so a tap can toggle pause safely. */
    fun isPlaying(key: String): Boolean
}

/** Uses the existing app-wide player, audio focus arbitration and cancellation-safe preparation. */
internal object NativeVoiceReviewPlayback : VoiceReviewPlayback {
    /** Starts native preparation with no conversation auto-chain owner for this unsent take. */
    override suspend fun play(clip: VoiceReviewClip): Boolean =
        VoicePlaybackController.play(clip.key, clip.file) in
            setOf(
                VoicePlaybackController.PlaybackStartResult.Started,
                VoicePlaybackController.PlaybackStartResult.Resumed,
            )

    /** Retains position only when this preview still owns the player. */
    override fun pause(key: String) {
        if (VoicePlaybackController.state.value.key == key) VoicePlaybackController.pause()
    }

    /** Invalidates in-flight preparation only if this preview still owns its native key. */
    override fun stop(key: String) {
        if (VoicePlaybackController.state.value.key == key) VoicePlaybackController.stop()
    }

    /** Reads the native playing state without treating another clip as this review. */
    override fun isPlaying(key: String): Boolean {
        val state = VoicePlaybackController.state.value
        return state.key == key && state.isPlaying
    }
}

/**
 * Owns unsent recordings and their callbacks until explicit Send or disposal. Captured actions are
 * admitted by clip identity and the live account/runtime predicate; disposal can also revoke a Send
 * while the native sender reads the file, before it queues an attachment.
 */
@Stable
@Suppress("LongParameterList")
internal class VoiceRecordingReview(
    private val scope: CoroutineScope,
    private val ownerIsCurrent: () -> Boolean,
    private val send: (File, Long, () -> Boolean, (Boolean) -> Unit) -> Unit,
    private val onSendFailure: () -> Unit = {},
    private val onPlaybackFailure: () -> Unit = {},
    private val playback: VoiceReviewPlayback = NativeVoiceReviewPlayback,
    private val deleteFile: (File) -> Unit = { file -> runCatching { file.delete() } },
    private val onReviewPresenceChanged: (Boolean) -> Unit = {},
) {
    var clip: VoiceReviewClip? by mutableStateOf(null)
        private set
    var isSending: Boolean by mutableStateOf(false)
        private set
    private var pendingSend: Any? = null
    private var released = false
    private var playbackJob: Job? = null

    /** Receives the completed encoder file without queuing or uploading it. */
    fun offer(
        file: File,
        durationMs: Long,
    ): Boolean {
        if (!isCurrent() || durationMs <= 0L) {
            deleteFile(file)
            return false
        }
        clip?.let { discard(it) }
        clip = VoiceReviewClip(file, durationMs)
        onReviewPresenceChanged(true)
        return true
    }

    /** Replays or pauses only the clip whose visible control was invoked. */
    fun togglePlayback(expected: VoiceReviewClip) {
        if (!owns(expected) || isSending) return
        if (playback.isPlaying(expected.key)) {
            playback.pause(expected.key)
            return
        }
        playbackJob?.cancel()
        playbackJob =
            scope.launch {
                if (!owns(expected)) return@launch
                val started = playback.play(expected)
                if (!owns(expected)) {
                    playback.stop(expected.key)
                } else if (!started) {
                    onPlaybackFailure()
                }
            }
    }

    /** Discards this exact take, leaving any replacement take untouched. */
    fun discard(expected: VoiceReviewClip): Boolean {
        if (clip !== expected) return false
        stopPlayback(expected)
        pendingSend = null
        isSending = false
        clip = null
        // Disposal deletes private audio but retains the non-sensitive recreation warning.
        if (!released) onReviewPresenceChanged(false)
        deleteFile(expected.file)
        return true
    }

    /** Starts a fresh native locked recording only after discarding the current review. */
    fun recordAgain(
        expected: VoiceReviewClip,
        start: () -> Unit,
    ) {
        if (owns(expected) && !isSending && discard(expected)) start()
    }

    /** Retains the take until native queue acceptance; rejection leaves the same recording ready to retry. */
    @Suppress("TooGenericExceptionCaught") // Dispatch can fail synchronously; retain the take and rethrow cancellation.
    fun send(expected: VoiceReviewClip): Boolean {
        if (!owns(expected) || isSending) return false
        stopPlayback(expected)
        val token = Any()
        pendingSend = token
        isSending = true
        try {
            send(
                expected.file,
                expected.durationMs,
                { owns(expected) && pendingSend === token },
                { accepted -> finishSend(expected, token, accepted) },
            )
        } catch (failure: Exception) {
            finishSend(expected, token, false)
            if (failure is CancellationException) throw failure
        }
        return true
    }

    /** Ends only the matching attempt; accepted bytes now belong to the existing native upload/retry owner. */
    private fun finishSend(
        expected: VoiceReviewClip,
        token: Any,
        accepted: Boolean,
    ) {
        if (pendingSend !== token || clip !== expected) return
        pendingSend = null
        isSending = false
        if (accepted) {
            clip = null
            onReviewPresenceChanged(false)
            deleteFile(expected.file)
        } else if (isCurrent()) {
            onSendFailure()
        }
    }

    /** A quick native restart invalidates the preceding tail's review without affecting the new recorder. */
    fun discardForNewRecording() {
        clip?.let { discard(it) }
    }

    /** Revokes callbacks immediately, then removes the unsent private file and its player. */
    fun release() {
        if (released) return
        released = true
        clip?.let { discard(it) }
        playbackJob?.cancel()
        playbackJob = null
    }

    /** Guards both immediate UI actions and the sender's post-read queue boundary. */
    private fun isCurrent(): Boolean = !released && ownerIsCurrent()

    /** A callback captured from an older review cannot operate on its replacement. */
    private fun owns(expected: VoiceReviewClip): Boolean = clip === expected && isCurrent()

    /** Cancels preparation before stopping only this review's playback identity. */
    private fun stopPlayback(expected: VoiceReviewClip) {
        playbackJob?.cancel()
        playbackJob = null
        playback.stop(expected.key)
    }
}
