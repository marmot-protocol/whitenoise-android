@file:Suppress("TooManyFunctions")

package dev.ipf.whitenoise.android.audio

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import dev.ipf.whitenoise.android.state.ConversationDictationPreferences
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal const val CONVERSATION_DICTATION_PREFERENCES_NAME = "whitenoise"

private const val READINESS_UI_FRAME_MILLIS = 16L

/** Native recorder closure never mutates controller ownership from a capture thread. */
internal fun mainThreadDictationCaptureClosure(onClosed: () -> Unit): () -> Unit =
    {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onClosed()
        } else {
            Handler(Looper.getMainLooper()).post(onClosed)
        }
    }

/** Attachment may precede generation registration, so caller-audio failures always queue to main. */
internal fun mainThreadDictationCallerAudioFailure(
    listener: ConversationDictationRecognitionListener,
    acknowledge: (ConversationDictationCallerAudioFailure) -> Boolean,
): (ConversationDictationCallerAudioFailure) -> Unit =
    { failure ->
        Handler(Looper.getMainLooper()).post {
            listener.onCallerAudioFailure(failure) { acknowledge(failure) }
        }
    }

internal val ConversationDictationCallerAudioFailure.recognitionFailure: ConversationDictationFailure
    get() =
        when (this) {
            ConversationDictationCallerAudioFailure.BufferFull -> ConversationDictationFailure.AudioBufferFull
            ConversationDictationCallerAudioFailure.CaptureFailed -> ConversationDictationFailure.Unknown
            ConversationDictationCallerAudioFailure.PipeFailed -> ConversationDictationFailure.ProviderDisconnected
        }

@Suppress("MaxLineLength")
internal class AndroidConversationDictationPlatform(
    private val context: Context,
) : ConversationDictationPlatform {
    private var sessionRecognitionService: ComponentName? = null
    private var nextCallerAudioSessionId = 0L
    private val callerAudio =
        ConversationDictationCaptureOwner { ConversationDictationCallerAudio.open(++nextCallerAudioSessionId) }
    private var sessionProvider: ConversationDictationProviderChoice? = null
    private var providerPrepared = false
    private var sessionProviderCanRecord = false
    private val dictationPreferences by lazy { ConversationDictationPreferences(context) }

    override fun beginCaptureSession() = callerAudio.beginSession()

    /**
     * Remembers what each provider build answered about caller-supplied audio.
     *
     * This is Android platform-capability state, not White Noise protocol data: it describes an
     * installed package's behavior, so it belongs to the device and must survive the process that
     * learned it, or every launch would probe again.
     */
    private val callerAudioVerdicts =
        ConversationDictationCallerAudioVerdicts(
            read = { key -> callerAudioPreferences().getString(key, null) },
            write = { key, value -> callerAudioPreferences().edit().putString(key, value).apply() },
        )

    /** Reports the runtime microphone grant required before creating an app-owned recognizer. */
    override fun hasRecordAudioPermission(): Boolean {
        val granted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        conversationDictationDiagnostic("event=app_record_audio_permission granted=$granted")
        return granted
    }

    /** Leaves global microphone privacy to Android while preserving hard app-op denial. */
    @Suppress("DEPRECATION")
    override fun microphoneAccess(): ConversationDictationMicrophoneAccess {
        if (!hasRecordAudioPermission()) return ConversationDictationMicrophoneAccess.RuntimePermissionRequired
        // Android folds the global microphone toggle into its effective permission result. Do not
        // treat MODE_IGNORED as an app denial: starting recognition is what lets Android present
        // its native microphone-unblock prompt while White Noise stays on the current surface.
        // MODE_ERRORED is distinguishable: Android documents it as a hard denial that should fail.
        val mode =
            context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_RECORD_AUDIO,
                Process.myUid(),
                context.packageName,
            )
        val access =
            if (mode == AppOpsManager.MODE_ERRORED) {
                ConversationDictationMicrophoneAccess.AppOpDenied
            } else {
                ConversationDictationMicrophoneAccess.Granted
            }
        conversationDictationDiagnostic("event=app_record_audio_access mode=$mode access=${access.name}")
        return access
    }

    override fun prepareProviderSelection(): Boolean {
        val choices = discoverConversationDictationProviders(context).flatMap { it.choices }
        val saved = dictationPreferences.current().providerSelection
        val stale = saved != null && choices.none(saved::sameInstallation)
        if (stale) dictationPreferences.setProviderSelection(null)
        val androidSelected = selectedRecognitionService()
        sessionProvider =
            if (stale && choices.none { androidSelected != null && it.service == androidSelected }) {
                null
            } else {
                resolveConversationDictationProvider(androidSelected, saved, choices)
            }
        sessionRecognitionService = sessionProvider?.service
        sessionProviderCanRecord = providerCanRecord(sessionRecognitionService)
        providerPrepared = true
        return sessionProvider != null
    }

    /** Legacy preflight callers also explicitly begin a fresh provider resolution. */
    override fun recognitionConfigured(): Boolean {
        prepareProviderSelection()
        return sessionRecognitionService != null
    }

    override fun pinnedRecognitionConfigured(): Boolean = recognitionAvailable()

    override fun recognitionAvailable(): Boolean {
        if (!providerPrepared) prepareProviderSelection()
        return sessionRecognitionService != null && pinnedProviderAvailable()
    }

    override fun pinnedProviderStillAvailable(): Boolean = pinnedProviderAvailable()

    private fun pinnedProviderAvailable(): Boolean =
        sessionProvider?.let { pinned ->
            discoverConversationDictationProviders(context).flatMap { it.choices }.any(pinned::sameInstallation)
        } ?: false

    override fun speechProviderPackage(): String? {
        if (!providerPrepared) prepareProviderSelection()
        return sessionProvider?.packageName
    }

    override fun providerActivityIntent(): Intent {
        if (!pinnedProviderAvailable()) throw ConversationDictationProviderUnavailableException()
        return conversationDictationRecognitionActivityIntent(
            sessionProvider?.activity ?: throw ConversationDictationProviderUnavailableException(),
        )
    }

    override fun recognitionActivityAvailable(): Boolean =
        sessionProvider?.activity != null && pinnedProviderAvailable()

    /** Resolves provider UI off the main thread and posts at most one cancellable callback. */
    override fun checkRecognitionActivity(callback: (Boolean) -> Unit): ConversationDictationTimeoutHandle {
        val handler = Handler(Looper.getMainLooper())
        val cancelled = AtomicBoolean(false)
        val worker =
            thread(name = "dictation-readiness", isDaemon = true) {
                val available = runCatching(::recognitionActivityAvailable).getOrDefault(false)
                handler.postDelayed(
                    { if (!cancelled.get()) callback(available) },
                    READINESS_UI_FRAME_MILLIS,
                )
            }
        return ConversationDictationTimeoutHandle {
            cancelled.set(true)
            worker.interrupt()
        }
    }

    /** Creates one recognizer session pinned to Android's explicit or unambiguous installed service. */
    override fun createSession(
        listener: ConversationDictationRecognitionListener,
    ): ConversationDictationRecognitionSession {
        val selected =
            sessionRecognitionService?.takeIf { recognitionAvailable() }
                ?: throw ConversationDictationProviderUnavailableException()
        conversationDictationDiagnostic("event=create_recognizer ${selected.describeProvider()}")
        return AndroidConversationDictationRecognitionSession(
            context = context,
            recognitionService = selected,
            callerAudio = openCallerAudioStreamIfProviderCannotRecord(listener),
            listener = listener,
        )
    }

    /**
     * Captures in White Noise for an ordinary unselected provider, whose binding lacks the
     * microphone capability. Selected and preinstalled providers keep their own capture path.
     */
    @Suppress("MaxLineLength")
    private fun openCallerAudioStreamIfProviderCannotRecord(
        listener: ConversationDictationRecognitionListener,
    ): ConversationDictationCallerAudioStream? {
        val systemSelected = selectedRecognitionService() != null
        val providerRecords = sessionProviderCanRecord
        val supported = conversationDictationAudioSourceSupported()
        val capture =
            if (providerRecords || !supported) {
                null
            } else {
                callerAudio.acquire()
            }
        val source =
            capture?.openProviderStream(mainThreadDictationCallerAudioFailure(listener, capture::acknowledgeFailure))
        conversationDictationDiagnostic(
            "event=caller_audio_mode enabled=${source != null} " +
                "system_selected=$systemSelected provider_records=$providerRecords supported=$supported",
        )
        return source
    }

    /** Reports retained audio even after microphone closure. */
    override fun callerAudioHasPending(): Boolean = callerAudio.hasPending()

    /** Restores the original provider binding, not a later recording's capture mode. */
    override fun parkCallerAudio(): ConversationDictationParkedAudio? {
        val audio = callerAudio.park() ?: return null
        val provider = sessionProvider
        val service = sessionRecognitionService
        val providerCanRecord = sessionProviderCanRecord
        val prepared = providerPrepared
        return object : ConversationDictationParkedAudio {
            override fun restore(): Boolean {
                if (!audio.restore()) return false
                sessionProvider = provider
                sessionRecognitionService = service
                sessionProviderCanRecord = providerCanRecord
                providerPrepared = prepared
                return true
            }

            override fun discard() = audio.discard()
        }
    }

    override fun acknowledgeRetainedCallerAudioFailure() = callerAudio.acknowledgeRetainedFailure()

    /** Uses capture activity rather than recognizer callbacks delayed by chunk buffering. */
    override fun callerAudioSilenceMillis(): Long? = callerAudio.silenceMillis()

    /** Returns recorder closure on the main thread before creating another recognizer. */
    override fun finishCallerAudioCapture(onClosed: () -> Unit): Boolean = callerAudio.finish(onClosed)

    override fun forceCallerAudioCaptureClosure(onClosed: () -> Unit): Boolean = callerAudio.forceClose(onClosed)

    /** Detaches the logical capture before discarding PCM and notifying the caller of recorder closure. */
    override fun discardCallerAudio(onClosed: () -> Unit): Boolean = callerAudio.discard(onClosed)

    /** Reports what is already known about the resolved provider build, without asking it. */
    override fun callerAudioRequirement(): ConversationDictationCallerAudioRequirement {
        if (!providerPrepared) prepareProviderSelection()
        if (sessionProvider != null && sessionRecognitionService == null) {
            return ConversationDictationCallerAudioRequirement.Unsupported
        }
        val requirement = knownCallerAudioRequirement(callerAudioProvider())
        conversationDictationDiagnostic("event=caller_audio_known requirement=${requirement.name}")
        return requirement
    }

    /**
     * Asks the resolved provider build whether it reads a caller-supplied descriptor, records a
     * conclusive answer for that exact build, and reports the answer once on the main thread.
     */
    override fun probeCallerAudioSupport(
        callback: (ConversationDictationCallerAudioRequirement) -> Unit,
    ): ConversationDictationTimeoutHandle {
        val provider = callerAudioProvider()
        val known = knownCallerAudioRequirement(provider)
        if (known != ConversationDictationCallerAudioRequirement.Unknown || provider == null) {
            // Nothing to ask: either the answer is already settled, or no provider build was
            // resolved and the ordinary session preflight owns that failure.
            callback(known)
            return ConversationDictationTimeoutHandle {}
        }
        return ConversationDictationCallerAudioProbe(
            context = context,
            provider = provider,
            verdicts = callerAudioVerdicts,
            resolveProvider = ::callerAudioProvider,
            callback = callback,
        ).start()
    }

    /**
     * Answers from installed state alone.
     *
     * A selected or preinstalled recognizer opens the microphone for itself, so caller-supplied audio never
     * applies. Before Tiramisu the platform defines no caller-audio extras at all, so an app-owned
     * session cannot supply audio and the provider's own UI is genuinely the only surface that can
     * capture. Otherwise the answer is whatever this provider build has already established.
     */
    private fun knownCallerAudioRequirement(
        provider: ConversationDictationCallerAudioProvider?,
    ): ConversationDictationCallerAudioRequirement =
        when {
            provider != null && sessionProviderCanRecord -> ConversationDictationCallerAudioRequirement.NotNeeded
            !conversationDictationAudioSourceSupported() -> ConversationDictationCallerAudioRequirement.Unsupported
            provider == null -> ConversationDictationCallerAudioRequirement.Unknown
            else -> callerAudioVerdicts.recorded(provider.component, provider.versionCode)
        }

    /**
     * Names the provider build a verdict can belong to.
     *
     * Both the component and its version code are required: a verdict describes one installed
     * build, so a version lookup that fails must leave the answer unknown rather than key a cache
     * entry on a version this app never read.
     */
    private fun callerAudioProvider(): ConversationDictationCallerAudioProvider? {
        if (!providerPrepared) prepareProviderSelection()
        val component = sessionRecognitionService
        if (component == null || !pinnedProviderAvailable()) return null
        return providerVersionCode(component.packageName)?.let { versionCode ->
            ConversationDictationCallerAudioProvider(component, versionCode)
        }
    }

    /** Preinstalled recognizers also receive Android's microphone binding capability. */
    private fun providerCanRecord(component: ComponentName?): Boolean {
        if (component != null && selectedRecognitionService() == component) return true
        return component?.let {
            runCatching {
                val application = context.packageManager.getApplicationInfo(it.packageName, 0)
                application.flags and ApplicationInfo.FLAG_SYSTEM != 0
            }.getOrDefault(false)
        } ?: false
    }

    private fun providerVersionCode(packageName: String): Long? =
        runCatching {
            PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(packageName, 0))
        }.getOrNull()

    private fun callerAudioPreferences() =
        context.getSharedPreferences(CONVERSATION_DICTATION_PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Parses Android's secure setting for the currently selected recognition service. */
    private fun selectedRecognitionService(): ComponentName? =
        conversationDictationRecognitionServiceComponent(
            Settings.Secure.getString(
                context.contentResolver,
                VOICE_RECOGNITION_SERVICE_SETTING,
            ),
        )

    private fun ComponentName?.describeProvider(): String {
        if (this == null) return "component=none"
        val versionCode = providerVersionCode(packageName)
        return "component=${flattenToShortString()} version_code=${versionCode ?: "unknown"}"
    }
}

/**
 * Asks one provider build whether it transcribes audio the caller supplies.
 *
 * The probe opens no microphone. It hands over a descriptor that is already at end of audio and
 * never constructs an [android.media.AudioRecord]; a provider that ignores the descriptor reaches
 * for a microphone the platform will not let an unprivileged recognizer open, which is exactly the
 * refusal being detected.
 */
private class ConversationDictationCallerAudioProbe(
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

private class AndroidConversationDictationRecognitionSession(
    context: Context,
    recognitionService: ComponentName,
    private val callerAudio: ConversationDictationCallerAudioStream?,
    private val listener: ConversationDictationRecognitionListener,
) : ConversationDictationRecognitionSession {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context, recognitionService)
    private val recognitionIntent = conversationDictationRecognitionIntent()
    private var started = false
    private var destroyed = false
    private var callerAudioCapturing = false
    private val captureFinished = AtomicReference<(() -> Unit)?>(null)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** One exactly-once fence for this recognition generation; createSession owns the next one. */
    private val callerAudioCompletionBarrier =
        ConversationDictationCallerAudioCompletionBarrier(::dispatchOnMain)

    init {
        recognizer.setRecognitionListener(
            @Suppress("TooManyFunctions")
            object : RecognitionListener {
                /** Reports that the platform recognizer is ready for audio. */
                override fun onReadyForSpeech(params: Bundle?) = listener.onReady()

                /** Reports the first detected speech frame. */
                override fun onBeginningOfSpeech() = listener.onBeginningOfSpeech()

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                /** Reports provider end-of-speech while capture closure remains callback-owned. */
                override fun onEndOfSpeech() {
                    if (!callerAudioCapturing) reportCaptureFinished()
                    listener.onEndOfSpeech()
                }

                /** Defers a terminal platform error until caller-owned audio has closed. */
                override fun onError(error: Int) {
                    val mapped = error.toConversationDictationFailure()
                    deliverAfterCallerAudioCloses {
                        conversationDictationDiagnostic("event=platform_error code=$error failure=${mapped.name}")
                        listener.onError(mapped)
                    }
                }

                /** Defers the final transcript until caller-owned audio has closed. */
                override fun onResults(results: Bundle?) {
                    deliverAfterCallerAudioCloses {
                        listener.onResult(
                            results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull(),
                        )
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    conversationDictationDiagnostic(
                        "event=platform_partial_results has_text=${partialResults.hasRecognitionText()}",
                    )
                }

                override fun onSegmentResults(segmentResults: Bundle) {
                    conversationDictationDiagnostic(
                        "event=platform_segment_results has_text=${segmentResults.hasRecognitionText()}",
                    )
                }

                override fun onEndOfSegmentedSession() {
                    conversationDictationDiagnostic("event=platform_segmented_session_end")
                }

                override fun onLanguageDetection(results: Bundle) {
                    conversationDictationDiagnostic("event=platform_language_detection")
                }

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?,
                ) {
                    conversationDictationDiagnostic("event=platform_event type=$eventType")
                }
            },
        )
    }

    /** Starts listening for one final free-form result without requesting partial hypotheses. */
    override fun start() {
        check(!started && !destroyed)
        started = true
        val capturing = callerAudio?.start() == true
        callerAudioCapturing = capturing
        conversationDictationDiagnostic("event=platform_start_listening caller_audio=$capturing")
        val intent = startIntent(capturing)
        // Both descriptors stay open until capture ends. SpeechRecognizer.startListening only
        // queues the request on the main looper, so the intent, and the descriptor inside it, is
        // marshalled after this returns; closing here would hand the provider a dead descriptor
        // and fail the session with ERROR_CLIENT.
        runCatching { recognizer.startListening(intent) }
            .onFailure {
                deliverAfterCallerAudioCloses {
                    listener.onError(ConversationDictationFailure.Unknown)
                }
            }
    }

    /**
     * Adds White Noise's own capture to the request when capture actually started. Capture that
     * refuses to start falls back to the provider's own microphone so a configuration that already
     * works keeps working.
     */
    private fun startIntent(capturing: Boolean): Intent {
        val source = callerAudio
        if (capturing && source != null) {
            return recognitionIntent.withConversationDictationAudioSource(source.providerEnd)
        }
        if (source != null) {
            conversationDictationDiagnostic("event=caller_audio_unavailable fallback=provider_microphone")
        }
        return recognitionIntent
    }

    /** Requests the provider to finish the current utterance and return its final result. */
    override fun stop(onAudioCaptureFinished: () -> Unit) {
        conversationDictationDiagnostic("event=platform_stop_listening")
        captureFinished.set(onAudioCaptureFinished)
        // Caller-audio requests finish on descriptor EOF; stopListening would race the final chunk.
        if (callerAudioCapturing) {
            callerAudioCompletionBarrier.requireCaptureClosure()
            callerAudio?.finishCapture(::reportCaptureFinished)
        } else {
            recognizer.stopListening()
        }
    }

    /** Requests a final result when no capture acknowledgement is required. */
    override fun stop() = stop {}

    /** Delivers a terminal provider callback only after caller-owned audio has fully closed. */
    private fun deliverAfterCallerAudioCloses(delivery: () -> Unit) {
        if (!callerAudioCapturing) {
            reportCaptureFinished()
            callerAudioCompletionBarrier.deliverImmediately(delivery)
            return
        }
        val source = callerAudio
        if (source == null) {
            // Defensive only: callerAudioCapturing can become true only when this immutable stream starts.
            callerAudioCompletionBarrier.deliverImmediately(delivery)
            return
        }
        callerAudioCompletionBarrier.deliver(source::onFeedClosed, source::onCaptureClosed, delivery)
    }

    /** Serializes terminal callback decisions with UI actions on Android's main thread. */
    private fun dispatchOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    /** Delivers the retained capture acknowledgement once on the main thread. */
    private fun reportCaptureFinished() {
        val callback = captureFinished.getAndSet(null) ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            callback()
        } else {
            mainHandler.post(callback)
        }
    }

    /** Cancels provider work and acknowledges closure of caller-owned capture. */
    override fun cancel(onAudioCaptureFinished: () -> Unit) {
        conversationDictationDiagnostic("event=platform_cancel")
        captureFinished.set(onAudioCaptureFinished)
        if (callerAudioCapturing) callerAudio?.cancel() else reportCaptureFinished()
        recognizer.cancel()
    }

    /** Cancels provider work when no capture acknowledgement is required. */
    override fun cancel() = cancel {}

    /** Releases the recognizer and acknowledges closure of caller-owned capture. */
    override fun destroy(onAudioCaptureFinished: () -> Unit) {
        captureFinished.set(onAudioCaptureFinished)
        if (callerAudioCapturing) callerAudio?.cancel() else reportCaptureFinished()
        if (destroyed) return
        destroyed = true
        conversationDictationDiagnostic("event=platform_destroy")
        recognizer.destroy()
        callerAudio?.closeProviderEnd()
        reportCaptureFinished()
    }

    /** Acknowledges only this recognizer generation’s caller-audio chunk after its final result. */
    override fun acknowledgeCallerAudio(): Boolean = callerAudio?.acknowledge() == true

    override fun callerAudioRequest(): ConversationDictationAudioRequest? = callerAudio?.request()

    override fun acknowledgeCallerAudio(transcript: String): ConversationDictationAudioSettlement? =
        callerAudio?.acknowledge(transcript)

    /** Returns the stable identity of this recognizer generation's exact caller-audio chunk. */
    override fun callerAudioChunkId(): Long? = callerAudio?.chunkId()

    /** Returns capture-side speech evidence for this recognizer generation's exact chunk. */
    override fun callerAudioContainsSpeech(): Boolean? = callerAudio?.containsSpeech()

    /** Distinguishes a successful exact-chunk write from cancellation, stalls and broken pipes. */
    override fun callerAudioFullyFed(): Boolean = callerAudio?.fullyFed() == true

    /** Reports exact final-chunk ownership while the sealed PCM still belongs to this generation. */
    override fun callerAudioIsFinalChunk(): Boolean = callerAudio?.isFinalChunk() == true

    /** Returns this recognizer generation’s unacknowledged caller audio for a replacement request. */
    override fun retryCallerAudio(): Boolean = callerAudio?.retry() == true

    /** Coalesces a provider-rejected short chunk with following PCM before the next generation. */
    override fun retryCallerAudioWithFollowingAudio(): Boolean = callerAudio?.retryWithFollowingAudio() == true

    override fun retryCallerAudioWithContext(): Boolean = callerAudio?.retryWithContext() == true

    /** Reports the actual capture mode after start; a failed caller-audio start falls back safely. */
    override fun usesCallerAudioCapture(): Boolean = callerAudioCapturing

    /** Releases the recognizer when no capture acknowledgement is required. */
    override fun destroy() = destroy {}
}

/** Reports only whether a callback contained text, never the recognized content. */
private fun Bundle?.hasRecognitionText(): Boolean =
    this
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.any { it.isNotBlank() } == true

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
