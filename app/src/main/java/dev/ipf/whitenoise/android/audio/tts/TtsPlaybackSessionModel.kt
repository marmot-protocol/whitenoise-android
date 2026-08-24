package dev.ipf.whitenoise.android.audio.tts

/**
 * What the background playback surface (foreground-service notification and
 * MediaSession) shows for one controller state. Pure mapping so the
 * notification/session contract is unit-testable without Android.
 *
 * Metadata is deliberately generic: the lock screen and notification shade
 * never see sender names, chat titles, message text, or decrypted previews —
 * only that White Noise is reading messages.
 */
internal data class TtsPlaybackSessionModel(
    val isActive: Boolean,
    val isPlaying: Boolean,
    val navigationEnabled: Boolean,
) {
    companion object {
        fun from(state: TtsState): TtsPlaybackSessionModel =
            when (state) {
                is TtsState.Speaking ->
                    TtsPlaybackSessionModel(isActive = true, isPlaying = true, navigationEnabled = true)

                is TtsState.Paused ->
                    TtsPlaybackSessionModel(isActive = true, isPlaying = false, navigationEnabled = true)

                // Idle and Error both mean nothing can play: the queue is
                // already cleared, so a lingering service would hold a stale
                // notification and an idle foreground lease.
                is TtsState.Idle, is TtsState.Error ->
                    TtsPlaybackSessionModel(isActive = false, isPlaying = false, navigationEnabled = false)
            }
    }
}
