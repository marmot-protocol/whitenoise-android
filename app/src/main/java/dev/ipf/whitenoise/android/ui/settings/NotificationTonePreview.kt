package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.media.MediaPlayer
import dev.ipf.whitenoise.android.notifications.NotificationTone

/** Screen-owned audition only. Real notifications are always played by Android's channel policy. */
internal class NotificationTonePreview(
    private val context: Context,
) {
    private var player: MediaPlayer? = null

    // MediaPlayer exposes several platform failure types; all must release the screen-owned player.
    @Suppress("TooGenericExceptionCaught")
    fun play(
        tone: NotificationTone,
        onStarted: () -> Unit = {},
        onFailure: () -> Unit = {},
        onFinished: () -> Unit,
    ) {
        stop()
        val next = MediaPlayer()
        player = next
        try {
            next.setAudioAttributes(NotificationTone.audioAttributes)
            context.resources.openRawResourceFd(tone.audio).use {
                next.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            next.setOnCompletionListener {
                if (player === next) {
                    stop()
                    onFinished()
                }
            }
            next.setOnErrorListener { _, _, _ ->
                if (player === next) {
                    stop()
                    onFailure()
                }
                true
            }
            next.setOnPreparedListener {
                if (player === next) {
                    try {
                        next.start()
                        onStarted()
                    } catch (_: Exception) {
                        stop()
                        onFailure()
                    }
                }
            }
            next.prepareAsync()
        } catch (failure: Exception) {
            stop()
            throw failure
        }
    }

    fun stop() {
        player?.release()
        player = null
    }
}
