package dev.ipf.whitenoise.android.audio

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asks one provider build whether it transcribes audio the caller supplies.
 *
 * The probe opens no microphone. It hands over a descriptor that is already at end of audio and
 * never constructs an [android.media.AudioRecord]; a provider that ignores the descriptor reaches
 * for a microphone the platform will not let an unprivileged recognizer open, which is exactly the
 * refusal being detected.
 */
internal class ConversationDictationCallerAudioProbe(
    private val context: Context,
    private val provider: ConversationDictationCallerAudioProvider,
    private val verdicts: ConversationDictationCallerAudioVerdicts,
    private val resolveProvider: () -> ConversationDictationCallerAudioProvider?,
    private val callback: (ConversationDictationCallerAudioRequirement) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val settled = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val expire = Runnable { settle(ConversationDictationCallerAudioRequirement.Unknown) }
    private val release = Runnable(::releaseNow)
    private var recognizer: SpeechRecognizer? = null
    private var source: ParcelFileDescriptor? = null

    /** Starts the session on the main looper, which is the only thread a recognizer accepts. */
    fun start(): ConversationDictationTimeoutHandle {
        handler.post(::begin)
        handler.postDelayed(expire, PROBE_TIMEOUT_MILLIS)
        return ConversationDictationTimeoutHandle {
            cancelled.set(true)
            handler.removeCallbacks(expire)
            handler.post(release)
        }
    }

    private fun begin() {
        if (cancelled.get() || settled.get()) return
        conversationDictationDiagnostic(
            "event=caller_audio_probe_session component=${provider.component.flattenToShortString()}",
        )
        runCatching(::startProbeSession)
            .onFailure { failure ->
                // Setup failure says nothing about the descriptor, so it must not be recorded.
                conversationDictationDiagnostic(
                    "event=caller_audio_probe_setup_failed type=${failure.javaClass.simpleName}",
                )
                settle(ConversationDictationCallerAudioRequirement.Unknown)
            }
    }

    private fun startProbeSession() {
        val descriptor =
            checkNotNull(conversationDictationEmptyCallerAudioSource()) { "caller-audio probe source unavailable" }
        // The descriptor stays open until this probe settles. startListening only queues the
        // request on the main looper, so the intent, and the descriptor inside it, is marshalled
        // after this returns; closing here would hand the provider a dead descriptor.
        source = descriptor
        val created = SpeechRecognizer.createSpeechRecognizer(context, provider.component)
        recognizer = created
        created.setRecognitionListener(ProbeListener())
        created.startListening(
            conversationDictationRecognitionIntent().withConversationDictationAudioSource(descriptor),
        )
    }

    /**
     * Applies the first terminal answer and ignores every later one.
     *
     * The answer belongs to the build that produced it, so a provider replaced during the probe
     * invalidates it: neither the verdict nor the routing decision may be applied to a different
     * build.
     */
    private fun settle(requirement: ConversationDictationCallerAudioRequirement) {
        if (!settled.compareAndSet(false, true)) return
        handler.removeCallbacks(expire)
        // Teardown runs on a later main-looper turn so the recognizer is never destroyed from
        // inside its own callback, and the descriptor outlives this dispatch.
        handler.post(release)
        if (cancelled.get()) return
        handler.post { deliver(requirement) }
    }

    /**
     * Records and reports the answer once teardown has run.
     *
     * Both the cache write and the report are fenced here rather than at the terminal callback, so
     * a probe cancelled while teardown was pending leaves no verdict behind and reports nothing.
     */
    private fun deliver(requirement: ConversationDictationCallerAudioRequirement) {
        if (cancelled.get()) return
        val current = resolveProvider()
        val answered = if (current == provider) requirement else ConversationDictationCallerAudioRequirement.Unknown
        conversationDictationDiagnostic(
            "event=caller_audio_probe_settled requirement=${answered.name} same_provider=${current == provider}",
        )
        // An inconclusive answer, including one about a provider build that changed underneath this
        // probe, records nothing so the question can be asked again.
        verdicts.record(provider.component, provider.versionCode, answered)
        callback(answered)
    }

    private fun releaseNow() {
        // Cancelling first stops a still-running session before its recognizer goes away, so the
        // provider is not left holding a descriptor this probe is about to close.
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { source?.close() }
        source = null
    }

    /** Turns the provider's first terminal callback into a caller-audio answer. */
    private inner class ProbeListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            conversationDictationDiagnostic("event=caller_audio_probe_error code=$error")
            settle(conversationDictationClassifyCallerAudioProbe(error))
        }

        override fun onResults(results: Bundle?) {
            settle(conversationDictationClassifyCallerAudioProbe(null))
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onEvent(
            eventType: Int,
            params: Bundle?,
        ) = Unit
    }

    private companion object {
        /**
         * An empty utterance answers as fast as the provider can bind, but a cold provider process
         * still has to start. A probe that outlives this bound established nothing.
         */
        const val PROBE_TIMEOUT_MILLIS = 4_000L
    }
}
