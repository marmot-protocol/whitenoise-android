package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import android.provider.Settings as AndroidSettings

/** The one action that can clear a given dictation failure without leaving White Noise's controls. */
internal enum class ConversationDictationRecovery { Retry, AppSettings, SpeechProviderSetup }

/**
 * A provider that is missing, or that refuses an app-owned caller it cannot attribute, is only
 * fixable inside the speech service itself. Retrying the same unusable provider cannot succeed, and
 * White Noise never opens the provider's own recognition screen in its place.
 */
internal fun dictationFailureRecovery(reason: ConversationDictationFailure): ConversationDictationRecovery =
    when (reason) {
        ConversationDictationFailure.PermissionPermanentlyDenied -> ConversationDictationRecovery.AppSettings
        ConversationDictationFailure.ProviderAccessRejected,
        ConversationDictationFailure.ProviderUnavailable,
        -> ConversationDictationRecovery.SpeechProviderSetup
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
 * Opens the speech service itself, because that is the only place a user can finish setting it up:
 * granting its own microphone permission, and running whatever setup makes it usable as the system
 * recognizer. Android 17 routes ACTION_VOICE_INPUT_SETTINGS to the digital-assistant role, which
 * cannot select a package that only provides a RecognitionService, so that screen is a dead end and
 * is no longer offered first. Falling back to the provider's app details, then to White Noise's own,
 * keeps the action from ever being inert.
 */
internal fun openSpeechProviderSetup(
    context: Context,
    providerPackage: String?,
) {
    val candidates = mutableListOf<Intent>()
    providerPackage?.let { provider ->
        context.packageManager.getLaunchIntentForPackage(provider)?.let(candidates::add)
        candidates +=
            Intent(
                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", provider, null),
            )
    }
    candidates +=
        Intent(
            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
    startFirstResolvable(context, *candidates.toTypedArray())
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
