package dev.ipf.whitenoise.android.state

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.GroupEventKindFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.WipeOutcomeFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoiceDraftStore
import dev.ipf.whitenoise.android.audio.VoiceNoteForegroundService
import dev.ipf.whitenoise.android.audio.VoiceNoteKey
import dev.ipf.whitenoise.android.audio.VoiceNoteSessionOwner
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.audio.VoiceRecordingSaveException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                afterHeldRelease = {
                    hooks.onReleasedRecordingComplete.takeIf { voiceNotePreferences.sendHeldOnRelease }
                },
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

/** Stops both microphone owners while the account's local data is still available. */
internal fun WhiteNoiseAppState.stopCaptureOwners(account: String) {
    conversationDictation.onAccountUnavailable(account)
    voiceNotes.stopAccountCapture(account)
}

/** Sign-out stops capture and retires the account's voice drafts. */
internal fun WhiteNoiseAppState.releaseCaptureOwners(account: String) {
    stopCaptureOwners(account)
    voiceNotes.purgeAccountDrafts(account)
}

/** Settles the confirmed native wipe and its local voice drafts together, even if the caller cancels. */
internal suspend fun WhiteNoiseAppState.wipeAccountAndPurgeVoiceDrafts(account: String): WipeOutcomeFfi {
    currentCoroutineContext().ensureActive()
    return withContext(NonCancellable) {
        marmotIo { signOutAndWipe(account) }.also { outcome ->
            if (outcome.localCleanup.completed) voiceNotes.purgeAccountDrafts(account)
        }
    }
}

/** Stops both capture owners for a removed chat and reports whether its saved voice files were deleted. */
internal fun WhiteNoiseAppState.releaseCaptureTarget(
    account: String,
    groupIdHex: String,
): Boolean {
    conversationDictation.onTargetRemoved(account, groupIdHex)
    return voiceNotes.onTargetRemoved(account, groupIdHex)
}

/** Releases capture on an authoritative eviction and retries a purge while the group still reports removal. */
internal fun WhiteNoiseAppState.releaseEvictedCapture(
    account: String?,
    previous: AppGroupRecordFfi?,
    current: AppGroupRecordFfi,
) {
    if (account == null || !current.selfMembership.isGroupEviction()) return
    if (previous?.selfMembership?.isGroupEviction() != true ||
        voiceNotes.hasPendingTargetPurge(VoiceNoteKey(account, current.groupIdHex))
    ) {
        releaseCaptureTarget(account, current.groupIdHex)
    }
}

/** Schedules readback for a group update that may end capture while its conversation or chat stream is closed. */
internal fun WhiteNoiseAppState.checkCaptureEvictionOnRuntimeEvent(
    event: MarmotEventFfi,
    runtime: MarmotInterface,
    scope: CoroutineScope,
) {
    val key =
        when (event) {
            is MarmotEventFfi.GroupStateUpdated -> VoiceNoteKey(event.accountLabel, event.groupIdHex)
            is MarmotEventFfi.GroupEvent ->
                if (event.event is GroupEventKindFfi.LocalGroupCopyTerminated) {
                    VoiceNoteKey(event.accountLabel, event.groupIdHex)
                } else {
                    null
                }
            else -> null
        } ?: return
    scope.launch(Dispatchers.Main.immediate) { reconcileCaptureEviction(key, runtime) }
}

/** On the capture owner's dispatcher, confirms native membership before revoking capture or purging its draft. */
internal suspend fun WhiteNoiseAppState.reconcileCaptureEviction(
    key: VoiceNoteKey,
    runtime: MarmotInterface,
) {
    val (account, group) = key
    if (marmotOrNull() !== runtime) return
    if (!voiceNotes.needsTargetCleanup(key) && !conversationDictation.isOwnedBy(account, group)) return

    val epoch = runtimeGeneration
    val membership =
        runCatchingCancellable {
            withContext(Dispatchers.IO) { runtime.chatListRow(account, group)?.selfMembership }
        }.getOrNull()
    // Native membership belongs to this account and chat even if another profile was selected during readback.
    if (membership?.isGroupEviction() == true && epoch == runtimeGeneration && marmotOrNull() === runtime) {
        releaseCaptureTarget(account, group)
    }
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
