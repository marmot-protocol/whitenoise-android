package dev.ipf.whitenoise.android.audio

import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import org.robolectric.RuntimeEnvironment

/** Deterministic service host; provider PCM and promotion readiness remain independently controllable. */
internal class DictationForegroundTestHost(
    scope: CoroutineScope? = null,
    preference: ConversationDictationDeliveryMode = ConversationDictationDeliveryMode.PasteIntoDraft,
    autoReady: Boolean = true,
) : ConversationDictationServiceHost {
    val platform = DictationForegroundTestPlatform()
    var draft = TextFieldValue("")
    var revision = 0L
    val sent = mutableListOf<String>()
    override val conversationDictation =
        ConversationDictationController(
            platform = platform,
            readDraft = { _, _ -> ConversationDictationDraftSnapshot(draft, revision) },
            writeDraft = { _, _, expected, value ->
                if (expected != revision) {
                    null
                } else {
                    draft = value
                    revision += 1
                    revision
                }
            },
            disclosureAccepted = { true },
            markDisclosureAccepted = {},
            targetValidationScope = scope,
            startDurableSession = { _, ready ->
                if (autoReady) ready()
                true
            },
            stopDurableSession = {
                ConversationDictationForegroundService.stop(RuntimeEnvironment.getApplication())
            },
            silenceDeliveryMode = { preference },
            sendTranscriptIfOriginUnchanged = { request ->
                request.beginDispatch().also { if (it) sent += request.payload }
            },
        )

    init {
        conversationDictation.requestStart("account", "group", TextFieldValue(""))
    }

    /** Terminates controller ownership before a queued service start is delivered. */
    fun failRecognition() {
        platform.listener.onError(ConversationDictationFailure.Network)
    }
}

/** Minimal provider adapter shared by foreground ownership cases. */
internal class DictationForegroundTestPlatform : ConversationDictationPlatform {
    var sessionsCreated = 0
    var pendingCallerAudio = false
    var deferEmptyCaptureClosure = false
    var captureClosureCallback: (() -> Unit)? = null

    lateinit var listener: ConversationDictationRecognitionListener

    /** Test sessions always begin with record-audio permission. */
    override fun hasRecordAudioPermission(): Boolean = true

    /** Test sessions always expose an in-process recognizer. */
    override fun recognitionAvailable(): Boolean = true

    override fun callerAudioHasPending(): Boolean = pendingCallerAudio

    override fun finishCallerAudioCapture(onClosed: () -> Unit): Boolean {
        if (!pendingCallerAudio) return false
        onClosed()
        return true
    }

    override fun discardCallerAudio(onClosed: () -> Unit): Boolean =
        when {
            deferEmptyCaptureClosure -> {
                captureClosureCallback = onClosed
                true
            }
            !pendingCallerAudio -> false
            else -> {
                pendingCallerAudio = false
                onClosed()
                true
            }
        }

    /** Captures the listener and returns a no-op provider generation. */
    @Suppress("MaxLineLength")
    override fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession {
        this.listener = listener
        sessionsCreated++
        return object : ConversationDictationRecognitionSession {
            override fun start() = Unit

            override fun stop() = Unit

            override fun cancel() = Unit

            override fun destroy() = Unit
        }
    }
}
