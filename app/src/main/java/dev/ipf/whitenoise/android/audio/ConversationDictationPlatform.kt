@file:Suppress("TooManyFunctions")

package dev.ipf.whitenoise.android.audio

import android.content.Intent

internal interface ConversationDictationRecognitionListener {
    /** Reports that the provider is ready to receive speech. */
    fun onReady()

    /** Signals real speech so an armed post-segment silence timer cannot finish mid-utterance. */
    fun onBeginningOfSpeech() = Unit

    /** Reports that the provider stopped detecting speech and is preparing a final result. */
    fun onEndOfSpeech()

    /** Delivers one provider-final transcript segment, or null when no segment was produced. */
    fun onResult(transcript: String?)

    /** Delivers a normalized recognition failure for the active generation. */
    fun onError(error: ConversationDictationFailure)

    /** Only the current generation can consume a capture failure waiting on the main queue. */
    fun onCallerAudioFailure(
        failure: ConversationDictationCallerAudioFailure,
        acknowledge: () -> Boolean,
    ) {
        if (acknowledge()) onError(failure.recognitionFailure)
    }
}

internal interface ConversationDictationRecognitionSession {
    /** Begins recognition for this provider generation. */
    fun start()

    /** Requests a final result while preserving provider output already in flight. */
    fun stop()

    /** Requests a final result and reports when microphone capture has actually ended. */
    fun stop(onAudioCaptureFinished: () -> Unit) {
        stop()
        onAudioCaptureFinished()
    }

    /** Abandons recognition without requesting a final result. */
    fun cancel()

    /** Abandons recognition and reports when microphone capture has actually ended. */
    fun cancel(onAudioCaptureFinished: () -> Unit) {
        cancel()
        onAudioCaptureFinished()
    }

    /** Releases the provider resources owned by this generation. */
    fun destroy()

    /** Releases this generation and reports when microphone capture has actually ended. */
    fun destroy(onAudioCaptureFinished: () -> Unit) {
        destroy()
        onAudioCaptureFinished()
    }

    /** Releases the exact caller-audio chunk only after this generation's final was accepted. */
    fun acknowledgeCallerAudio(): Boolean = false

    /** Production caller-audio requests carry exact replay and newly covered PCM provenance. */
    fun callerAudioRequest(): ConversationDictationAudioRequest? = null

    /** Retry identity survives a feeder failure that already requeued its PCM. */
    fun callerAudioRequestVersion(): String? = callerAudioRequest()?.version

    /** Accepts only a fully fed request, atomically settling audio and its transcript edit. */
    fun acknowledgeCallerAudio(transcript: String): ConversationDictationAudioSettlement? = null

    /** Stable identity for the exact caller-audio chunk claimed by this generation. */
    fun callerAudioChunkId(): Long? = null

    /** Reports capture-side speech evidence for the exact caller-audio chunk, when available. */
    fun callerAudioContainsSpeech(): Boolean? = null

    /** Pipe closure alone is not proof that the provider received the entire owned chunk. */
    fun callerAudioFullyFed(): Boolean = false

    /** True only when this generation owns the sealed capture's last remaining chunk. */
    fun callerAudioIsFinalChunk(): Boolean = false

    /** Requeues the exact caller-audio chunk when this generation failed before a usable final. */
    fun retryCallerAudio(): Boolean = false

    /** Requeues a no-speech chunk with following audio so it cannot block later captured speech. */
    fun retryCallerAudioWithFollowingAudio(): Boolean = retryCallerAudio()

    fun retryCallerAudioWithContext(): Boolean = false

    /** Whether this generation successfully started White Noise-owned microphone capture. */
    fun usesCallerAudioCapture(): Boolean = false
}

internal class ConversationDictationProviderUnavailableException : IllegalStateException()

/** Effective microphone access after combining the runtime grant with Android's app-op policy. */
internal enum class ConversationDictationMicrophoneAccess {
    Granted,
    RuntimePermissionRequired,
    AppOpDenied,
    MicrophoneMuted,
}

internal interface ConversationDictationPlatform {
    /** Separates recorder ownership from a discarded capture belonging to an older gesture. */
    fun beginCaptureSession() = Unit

    /** Resolves and pins a new gesture; false asks for a provider before microphone access. */
    fun prepareProviderSelection(): Boolean = true

    /** Checks the already pinned identity without resolving preferences again. */
    fun pinnedRecognitionConfigured(): Boolean = recognitionConfigured()

    fun providerActivityIntent(): Intent = conversationDictationRecognitionActivityIntent()

    fun pinnedProviderStillAvailable(): Boolean = true

    /** Whether White Noise currently has permission to capture microphone audio. */
    fun hasRecordAudioPermission(): Boolean

    /** Distinguishes a requestable runtime denial from a settings-owned app-op denial. */
    fun microphoneAccess(): ConversationDictationMicrophoneAccess =
        if (hasRecordAudioPermission()) {
            ConversationDictationMicrophoneAccess.Granted
        } else {
            ConversationDictationMicrophoneAccess.RuntimePermissionRequired
        }

    /** Whether Android has an explicit selected recognition service to validate after permission. */
    fun recognitionConfigured(): Boolean = recognitionAvailable()

    /**
     * The package of the speech service this session resolved, or null when none is installed.
     * A failure the user can only clear inside that app needs somewhere to send them.
     */
    fun speechProviderPackage(): String? = null

    /** Whether an in-process recognition service can be created. */
    fun recognitionAvailable(): Boolean

    /** Whether Android can resolve the provider-owned recognition Activity. */
    fun recognitionActivityAvailable(): Boolean = true

    /** Checks provider-Activity readiness and returns a handle that invalidates late callbacks. */
    fun checkRecognitionActivity(callback: (Boolean) -> Unit): ConversationDictationTimeoutHandle {
        callback(recognitionActivityAvailable())
        return ConversationDictationTimeoutHandle {}
    }

    /**
     * What is already known about the resolved provider's ability to transcribe audio White Noise
     * captures itself.
     *
     * The default keeps every platform that opens the microphone for the app on the in-app path.
     */
    @Suppress("MaxLineLength")
    fun callerAudioRequirement(): ConversationDictationCallerAudioRequirement = ConversationDictationCallerAudioRequirement.NotNeeded

    /**
     * Establishes an unknown caller-audio answer without opening the microphone, then reports it
     * exactly once. The returned handle prevents both a late callback and a recorded verdict.
     */
    @Suppress("MaxLineLength")
    fun probeCallerAudioSupport(callback: (ConversationDictationCallerAudioRequirement) -> Unit): ConversationDictationTimeoutHandle {
        callback(callerAudioRequirement())
        return ConversationDictationTimeoutHandle {}
    }

    /** Whether capture still owns sealed, partial, or in-flight caller audio for the logical session. */
    fun callerAudioHasPending(): Boolean = false

    /** Detaches a closed recording while retaining its unresolved PCM for its original chat. */
    fun parkCallerAudio(): ConversationDictationParkedAudio? = null

    /** Explicit Retry accepts an existing capture failure without hiding later faults or proving closure. */
    fun acknowledgeRetainedCallerAudioFailure() = Unit

    /** Capture-side quiet time, or null when the provider owns microphone capture. */
    fun callerAudioSilenceMillis(): Long? = null

    /** Seals the current caller-audio tail when no provider generation currently owns it. */
    fun finishCallerAudioCapture(onClosed: () -> Unit): Boolean = false

    /** Interrupts a stalled recorder without discarding its already captured PCM. */
    fun forceCallerAudioCaptureClosure(onClosed: () -> Unit): Boolean = false

    /** Releases volatile caller audio at logical-session teardown. */
    fun discardCallerAudio(onClosed: () -> Unit): Boolean = false

    /** Creates one recognition generation whose callbacks are owned by [listener]. */
    fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession
}

internal fun interface ConversationDictationTimeoutHandle {
    /** Prevents this timeout or readiness callback from mutating controller state. */
    fun cancel()
}
