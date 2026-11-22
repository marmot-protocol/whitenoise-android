package dev.ipf.whitenoise.android.state

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoiceDraftStore
import dev.ipf.whitenoise.android.audio.VoiceNoteForegroundService
import dev.ipf.whitenoise.android.audio.VoiceNoteSessionOwner
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.audio.VoiceRecordingSaveException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/*
 * The process-level microphone owners, dictation and voice notes, share account and chat teardown here so neither
 * can outlive a signed-out account or a chat the account left.
 */

/** Builds the voice-note owner: recorders run on an app scope and drafts live in private no-backup storage. */
internal fun WhiteNoiseAppState.createVoiceNoteSessionOwner(): VoiceNoteSessionOwner {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    return VoiceNoteSessionOwner(
        store = VoiceDraftStore(appContext.noBackupFilesDir.resolve("voice-drafts")),
        newController = { _, hooks ->
            VoiceRecordingController(
                context = appContext,
                outputDirectory = hooks.outputDirectory,
                scope = scope,
                // A screen asks for the permission; a recorder started from elsewhere only checks the grant.
                onPermissionRequest = {
                    hooks.onPermissionRequest() ||
                        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                },
                onRecordingStarted = hooks.onRecordingStarted,
                onRecordingComplete = hooks.onRecordingComplete,
                onError = hooks.onError,
                // Read at record-start so a media-quality change applies to the next clip.
                bitrateProvider = { mediaQuality.audioBitrateBps },
                microphoneCaptures = microphoneCaptureCoordinator,
                onAutomaticStop = hooks.onAutomaticStop,
            )
        },
        presentError = { error -> presentVoiceRecordingFailure(error) },
        presentElsewhere = { present(R.string.voice_recording_elsewhere) },
        startForeground = { VoiceNoteForegroundService.start(appContext, it) },
    )
}

/** Sign-out or wipe ends both microphone owners' work for [account] and deletes its voice drafts. */
internal fun WhiteNoiseAppState.releaseCaptureOwners(account: String) {
    conversationDictation.onAccountUnavailable(account)
    voiceNotes.onAccountUnavailable(account)
}

/** The account left a chat: neither owner may keep capturing or holding a draft for it. */
internal fun WhiteNoiseAppState.releaseCaptureTarget(
    account: String,
    groupIdHex: String,
) {
    conversationDictation.onTargetRemoved(account, groupIdHex)
    voiceNotes.onTargetRemoved(account, groupIdHex)
}

/** Maps recorder failures to localized, privacy-safe user presentation. */
internal fun WhiteNoiseAppState.presentVoiceRecordingFailure(throwable: Throwable) {
    when {
        throwable is VoiceRecordingSaveException ->
            present(R.string.voice_recording_save_failed)
        throwable is IllegalStateException && throwable.message == "voice recording too short" ->
            present(R.string.voice_message_too_short)
        throwable.message?.contains("audio is in use", ignoreCase = true) == true ->
            presentFailure(
                R.string.voice_message_recording_failed,
                "VOICE_RECORDING_START",
                throwable,
                AppText.Resource(R.string.voice_message_microphone_busy),
            )
        else ->
            presentFailure(
                R.string.voice_message_recording_failed,
                "VOICE_RECORDING",
                throwable,
            )
    }
}
