package dev.ipf.whitenoise.android.audio

import android.content.ComponentName
import android.speech.SpeechRecognizer

/**
 * The installed provider build a caller-audio answer belongs to.
 *
 * Identity is the component plus its version code, so an answer is never applied to a different
 * provider, or to the upgrade that changed the behavior being described.
 */
internal data class ConversationDictationCallerAudioProvider(
    val component: ComponentName,
    val versionCode: Long,
) {
    val packageName: String
        get() = component.packageName
}

/** What is known about the resolved provider's ability to transcribe audio White Noise captures. */
internal enum class ConversationDictationCallerAudioRequirement {
    /** Android selected this recognizer, so it opens the microphone itself and this never applies. */
    NotNeeded,

    /** The provider read a caller descriptor when asked, so the in-app controls can drive it. */
    Supported,

    /** The provider ignores the descriptor, so only its own recognition UI can capture audio. */
    Unsupported,

    /** Not established yet, or the last answer said nothing about the descriptor. */
    Unknown,
}

/** Whether this requirement can be served by the in-app controls. */
internal val ConversationDictationCallerAudioRequirement.allowsInAppCapture: Boolean
    get() =
        this != ConversationDictationCallerAudioRequirement.Unsupported

/**
 * Classifies what one probe session established about caller-supplied audio.
 *
 * The probe hands the provider a source that is already at end of audio, so a provider that reads
 * the descriptor sees an empty utterance and answers with no match or an empty result. One that
 * ignores it reaches for the microphone the platform will not let a non-selected recognizer open,
 * and the framework refuses the session before the provider records anything.
 *
 * An engine that failed for its own reasons has answered nothing about the descriptor. Calling that
 * unsupported would send the user to the provider's own recognition UI, where the same engine would
 * fail the same way, so it stays unknown and the in-app path keeps the real failure visible.
 */
@Suppress("MaxLineLength")
internal fun conversationDictationClassifyCallerAudioProbe(errorCode: Int?): ConversationDictationCallerAudioRequirement =
    when (errorCode) {
        null,
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> ConversationDictationCallerAudioRequirement.Supported
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
        SpeechRecognizer.ERROR_CLIENT,
        -> ConversationDictationCallerAudioRequirement.Unsupported
        else -> ConversationDictationCallerAudioRequirement.Unknown
    }

/**
 * Remembers one provider build's answer.
 *
 * The key carries the version code because the answer is a property of the installed provider
 * build: the upgrade that adds descriptor support must not inherit the old build's refusal, and an
 * answer must survive the process that learned it so the probe runs once rather than per launch.
 */
internal class ConversationDictationCallerAudioVerdicts(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {
    fun recorded(
        component: ComponentName,
        versionCode: Long,
    ): ConversationDictationCallerAudioRequirement =
        when (read(key(component, versionCode))) {
            SUPPORTED -> ConversationDictationCallerAudioRequirement.Supported
            UNSUPPORTED -> ConversationDictationCallerAudioRequirement.Unsupported
            else -> ConversationDictationCallerAudioRequirement.Unknown
        }

    /** Stores a conclusive answer. An inconclusive probe records nothing so it can be retried. */
    fun record(
        component: ComponentName,
        versionCode: Long,
        requirement: ConversationDictationCallerAudioRequirement,
    ) {
        val value =
            when (requirement) {
                ConversationDictationCallerAudioRequirement.Supported -> SUPPORTED
                ConversationDictationCallerAudioRequirement.Unsupported -> UNSUPPORTED
                ConversationDictationCallerAudioRequirement.NotNeeded,
                ConversationDictationCallerAudioRequirement.Unknown,
                -> return
            }
        write(key(component, versionCode), value)
    }

    private fun key(
        component: ComponentName,
        versionCode: Long,
    ): String = "$KEY_PREFIX${component.flattenToString()}:$versionCode"

    private companion object {
        const val KEY_PREFIX = "caller_audio_support_component:"
        const val SUPPORTED = "supported"
        const val UNSUPPORTED = "unsupported"
    }
}
