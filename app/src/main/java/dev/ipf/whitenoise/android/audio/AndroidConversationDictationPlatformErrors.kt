package dev.ipf.whitenoise.android.audio

import android.speech.SpeechRecognizer

/**
 * Maps unstable Android speech errors without inventing a cause the error code does not carry.
 *
 * ERROR_SERVER used to join the two network codes, so a recognizer that never touches a network —
 * one whose model is missing, unselected or unloadable — was reported as a service that couldn't
 * connect. SpeechRecognizer has no code for "the provider has no model", and the app cannot tell an
 * on-device provider from a hosted one, so ERROR_SERVER claims only what it proves: the provider
 * could not produce a result. That is the provider's own to fix, and its recovery already opens it.
 * Only ERROR_NETWORK and ERROR_NETWORK_TIMEOUT still assert a network cause.
 */
internal fun Int.toConversationDictationFailure(): ConversationDictationFailure =
    when (this) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ConversationDictationFailure.PermissionDenied
        SpeechRecognizer.ERROR_NO_MATCH -> ConversationDictationFailure.NoMatch
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ConversationDictationFailure.NoSpeech
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> ConversationDictationFailure.Network
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> ConversationDictationFailure.ProviderDisconnected
        SpeechRecognizer.ERROR_AUDIO -> ConversationDictationFailure.MicrophoneInUse
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
        -> ConversationDictationFailure.RecognizerBusy
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        -> ConversationDictationFailure.ProviderUnavailable
        else -> ConversationDictationFailure.Unknown
    }
