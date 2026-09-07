package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import android.provider.Settings as AndroidSettings

/** The one action that can clear a given dictation failure without leaving White Noise's controls. */
internal enum class ConversationDictationRecovery { Retry, AppSettings, VoiceInputSettings }

/**
 * A provider that is missing, or that refuses an app-owned caller it cannot attribute, is only
 * fixable by choosing a speech service in Android's voice-input settings. Retrying the same
 * unusable provider cannot succeed, and White Noise never opens the provider's own recognition
 * screen in its place.
 */
internal fun dictationFailureRecovery(reason: ConversationDictationFailure): ConversationDictationRecovery =
    when (reason) {
        ConversationDictationFailure.PermissionPermanentlyDenied -> ConversationDictationRecovery.AppSettings
        ConversationDictationFailure.ProviderAccessRejected,
        ConversationDictationFailure.ProviderUnavailable,
        -> ConversationDictationRecovery.VoiceInputSettings
        else -> ConversationDictationRecovery.Retry
    }

/** Opens White Noise's Android permission page after a permanent microphone denial. */
internal fun openDictationAppSettings(context: Context) {
    startFirstResolvable(
        context,
        Intent(
            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ),
    )
}

/**
 * Opens Android's voice-input picker, where the selected recognition service is chosen. A build
 * without that screen falls back to this app's details page, which is the nearest place the user
 * can act, so the action is never inert.
 */
internal fun openVoiceInputSettings(context: Context) {
    startFirstResolvable(
        context,
        Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(
            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ),
    )
}

private fun startFirstResolvable(
    context: Context,
    vararg candidates: Intent,
) {
    for (intent in candidates) {
        val launched =
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        if (launched) return
    }
}
