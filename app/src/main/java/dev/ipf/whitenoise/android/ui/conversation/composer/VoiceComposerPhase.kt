package dev.ipf.whitenoise.android.ui.conversation.composer

import dev.ipf.whitenoise.android.audio.VoiceRecordingController

/**
 * What the composer shows for voice, derived from the recorder and the review on every read and never stored.
 * The pending and failed bubble are timeline states, not composer phases.
 */
internal enum class VoiceComposerPhase {
    Idle,
    Holding,
    Locked,
    Paused,
    Closing,
    Review,
    Sending,
    ;

    /** The microphone is open for this take. */
    val capturing: Boolean get() = this == Holding || this == Locked || this == Paused

    /** The recording strip covers the text field, including while the encoder closes a stopped take. */
    val showsStrip: Boolean get() = capturing || this == Closing

    /** The review overlay owns the composer. */
    val showsReview: Boolean get() = this == Review || this == Sending
}

/** The recorder and review flags a phase is derived from. */
internal data class VoiceComposerSignals(
    val recording: Boolean = false,
    val locked: Boolean = false,
    val paused: Boolean = false,
    val closing: Boolean = false,
    val reviewing: Boolean = false,
    val sending: Boolean = false,
)

/** A live take wins over an older review, and a finished review wins over the encoder's last cleanup. */
internal fun voiceComposerPhase(signals: VoiceComposerSignals): VoiceComposerPhase =
    when {
        signals.recording && signals.paused -> VoiceComposerPhase.Paused
        signals.recording && signals.locked -> VoiceComposerPhase.Locked
        signals.recording -> VoiceComposerPhase.Holding
        signals.reviewing && signals.sending -> VoiceComposerPhase.Sending
        signals.reviewing -> VoiceComposerPhase.Review
        signals.closing -> VoiceComposerPhase.Closing
        else -> VoiceComposerPhase.Idle
    }

/** Reads the live phase from the conversation's recorder and review. */
internal fun voiceComposerPhase(
    recorder: VoiceRecordingController?,
    review: VoiceRecordingReview?,
): VoiceComposerPhase =
    voiceComposerPhase(
        VoiceComposerSignals(
            recording = recorder?.isRecording == true,
            locked = recorder?.locked == true,
            paused = recorder?.isPaused == true,
            closing = recorder?.isClosing == true,
            reviewing = review?.clip != null,
            sending = review?.isSending == true,
        ),
    )
