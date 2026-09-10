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
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import dev.ipf.whitenoise.android.core.graphemeBoundaryAtOrAfter
import dev.ipf.whitenoise.android.core.graphemeBoundaryAtOrBefore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private const val DICTATION_DIAGNOSTIC_TAG = "WNDictation"

/** The Android preference file that owns dictation's platform-capability and consent state. */
private const val CONVERSATION_DICTATION_PREFERENCES_NAME = "whitenoise"

/** Emits PII-free speech-service state that survives release-build app-log export. */
internal fun conversationDictationDiagnostic(event: String) {
    Log.i(DICTATION_DIAGNOSTIC_TAG, event)
}

internal data class ConversationDictationTarget(
    val accountRef: String,
    val groupIdHex: String,
    val capturedDraft: TextFieldValue,
    val capturedDraftRevision: Long,
    val mode: ConversationDictationMode,
    val finishAfterSilenceMillis: Long? = null,
    val deliveryMode: ConversationDictationDeliveryMode = ConversationDictationDeliveryMode.PasteIntoDraft,
) {
    /** Compares the stable account and group identifiers without changing the captured target. */
    fun matchesConversation(
        accountRef: String,
        groupIdHex: String,
    ): Boolean =
        this.accountRef.equals(accountRef, ignoreCase = true) &&
            this.groupIdHex.equals(groupIdHex, ignoreCase = true)
}

internal enum class ConversationDictationMode {
    InApp,
    ProviderActivity,
}

internal enum class ConversationDictationDeliveryMode {
    PasteIntoDraft,
    SendOnFinish,
}

internal data class ConversationDictationDraftSnapshot(
    val value: TextFieldValue,
    val revision: Long,
)

/** Immutable auto-send request that the app must compare with the origin draft again at commit time. */
internal data class ConversationDictationSendRequest(
    val accountRef: String,
    val groupIdHex: String,
    val expectedDraftRevision: Long,
    val expectedDraftText: String,
    val payload: String,
    /** Called under the origin commit lock immediately before dispatch; false cancels an uncommitted send. */
    val beginDispatch: () -> Boolean = { true },
)

internal enum class ConversationDictationFailure {
    ProviderUnavailable,
    ProviderAccessRejected,
    PermissionDenied,
    PermissionPermanentlyDenied,
    MicrophoneMuted,
    MicrophoneInUse,
    NoSpeech,
    Network,
    ProviderDisconnected,
    RecognizerBusy,
    AudioBufferFull,
    TimedOut,
    Unknown,
}

/** PII-free provider readiness phases emitted for local diagnostics and tests. */
internal enum class ConversationDictationReadinessPhase {
    CheckingService,
    ServiceReady,
    ProviderUnavailable,
    TimedOut,
    Cancelled,
    LaunchingProvider,
}

internal data class ConversationDictationReadinessEvent(
    val phase: ConversationDictationReadinessPhase,
    val elapsedMillis: Long,
)

internal sealed interface ConversationDictationState {
    val sessionId: Long?
    val target: ConversationDictationTarget?

    data object Idle : ConversationDictationState {
        override val sessionId: Long? = null
        override val target: ConversationDictationTarget? = null
    }

    data class DisclosureRequired(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
    ) : ConversationDictationState

    data class PermissionRequired(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
    ) : ConversationDictationState

    data class Starting(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
    ) : ConversationDictationState

    /** A bounded, microphone-free availability check for the provider Activity. */
    data class CheckingProvider(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
        val startedAtElapsedMillis: Long,
    ) : ConversationDictationState

    data class Listening(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
        val startedAtElapsedMillis: Long,
    ) : ConversationDictationState

    data class Processing(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
    ) : ConversationDictationState

    /** The provider-owned recognition Activity is queued for launch. */
    data class ProviderActivityRequired(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
    ) : ConversationDictationState

    /** The provider-owned recognition Activity has been launched. */
    data class ProviderActivityActive(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
    ) : ConversationDictationState

    data class Failed(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
        val reason: ConversationDictationFailure,
    ) : ConversationDictationState

    /**
     * The origin draft changed in a way that made the captured insertion anchor
     * ambiguous. The transcript remains process-memory-only until the user
     * explicitly inserts, copies, or discards it.
     */
    data class ReviewRequired(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
        val transcript: String,
    ) : ConversationDictationState

    /** Dispatch began but its result is unconfirmed; only Copy or Discard is safe. */
    data class DeliveryUnknown(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
        val transcript: String,
    ) : ConversationDictationState
}

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

    /** Requeues the exact caller-audio chunk when this generation failed before a usable final. */
    fun retryCallerAudio(): Boolean = false
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

    /** Capture-side quiet time, or null when the provider owns microphone capture. */
    fun callerAudioSilenceMillis(): Long? = null

    /** Seals the current caller-audio tail when no provider generation currently owns it. */
    fun finishCallerAudioCapture(onClosed: () -> Unit): Boolean = false

    /** Releases volatile caller audio at logical-session teardown. */
    fun discardCallerAudio(onClosed: () -> Unit): Boolean = false

    /** Creates one recognition generation whose callbacks are owned by [listener]. */
    fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession
}

internal fun interface ConversationDictationTimeoutHandle {
    /** Prevents this timeout or readiness callback from mutating controller state. */
    fun cancel()
}

private data class ConversationDictationKey(
    val accountRef: String,
    val groupIdHex: String,
) {
    companion object {
        fun from(
            accountRef: String,
            groupIdHex: String,
        ): ConversationDictationKey = ConversationDictationKey(accountRef.lowercase(), groupIdHex.lowercase())
    }
}

/**
 * Process-level owner for composer dictation.
 *
 * The immutable target prevents a delayed recognizer callback from writing to
 * whichever conversation happens to be visible when recognition completes.
 * Raw audio is owned by the installed speech service and is never persisted by
 * White Noise; transcript text takes the normal per-conversation draft path.
 */
@Stable
@Suppress("LargeClass", "ReturnCount", "TooManyFunctions")
internal class ConversationDictationController internal constructor(
    private val platform: ConversationDictationPlatform,
    private val readDraft: (accountRef: String, groupIdHex: String) -> ConversationDictationDraftSnapshot,
    private val writeDraft: (
        accountRef: String,
        groupIdHex: String,
        expectedRevision: Long,
        value: TextFieldValue,
    ) -> Boolean,
    private val targetAvailable: (accountRef: String, groupIdHex: String) -> Boolean = { _, _ -> true },
    private val targetValidator: (suspend (accountRef: String, groupIdHex: String) -> Boolean)? = null,
    private val targetValidationScope: CoroutineScope? = null,
    private val onBeforeRecognition: () -> Unit = {},
    private val onAfterAudioCapture: () -> Unit = {},
    private val tryAcquireMicrophone: () -> Boolean = { true },
    private val releaseMicrophone: () -> Unit = {},
    private val startDurableSession: (String, () -> Unit) -> Boolean = { _, ready ->
        ready()
        true
    },
    private val stopDurableSession: () -> Unit = {},
    private val deliveryMode: () -> ConversationDictationDeliveryMode = {
        ConversationDictationDeliveryMode.PasteIntoDraft
    },
    private val sendTranscriptIfOriginUnchanged: suspend (ConversationDictationSendRequest) -> Boolean = { false },
    private val disclosureAccepted: () -> Boolean,
    private val markDisclosureAccepted: () -> Unit,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val scheduleTimeout: (delayMillis: Long, callback: () -> Unit) -> ConversationDictationTimeoutHandle =
        ::scheduleConversationDictationTimeout,
    private val finishAfterSilenceMillis: () -> Long? = { null },
    private val onReadinessEvent: (ConversationDictationReadinessEvent) -> Unit = {},
) {
    constructor(
        context: Context,
        readDraft: (accountRef: String, groupIdHex: String) -> ConversationDictationDraftSnapshot,
        writeDraft: (
            accountRef: String,
            groupIdHex: String,
            expectedRevision: Long,
            value: TextFieldValue,
        ) -> Boolean,
        targetAvailable: (accountRef: String, groupIdHex: String) -> Boolean,
        targetValidator: suspend (accountRef: String, groupIdHex: String) -> Boolean,
        targetValidationScope: CoroutineScope,
        onBeforeRecognition: () -> Unit,
        onAfterAudioCapture: () -> Unit,
        tryAcquireMicrophone: () -> Boolean,
        releaseMicrophone: () -> Unit,
        finishAfterSilenceMillis: () -> Long? = { null },
        deliveryMode: () -> ConversationDictationDeliveryMode = {
            ConversationDictationDeliveryMode.PasteIntoDraft
        },
        sendTranscriptIfOriginUnchanged: suspend (ConversationDictationSendRequest) -> Boolean = { false },
    ) : this(
        platform = AndroidConversationDictationPlatform(context.applicationContext),
        readDraft = readDraft,
        writeDraft = writeDraft,
        targetAvailable = targetAvailable,
        targetValidator = targetValidator,
        targetValidationScope = targetValidationScope,
        onBeforeRecognition = onBeforeRecognition,
        onAfterAudioCapture = onAfterAudioCapture,
        tryAcquireMicrophone = tryAcquireMicrophone,
        releaseMicrophone = releaseMicrophone,
        startDurableSession = { token, _ ->
            ConversationDictationForegroundService.start(context.applicationContext, token)
        },
        stopDurableSession = { ConversationDictationForegroundService.stop(context.applicationContext) },
        finishAfterSilenceMillis = finishAfterSilenceMillis,
        deliveryMode = deliveryMode,
        sendTranscriptIfOriginUnchanged = sendTranscriptIfOriginUnchanged,
        disclosureAccepted = {
            context
                .applicationContext
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(DISCLOSURE_ACCEPTED_KEY, false)
        },
        markDisclosureAccepted = {
            context
                .applicationContext
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(DISCLOSURE_ACCEPTED_KEY, true)
                .apply()
        },
    )

    var state: ConversationDictationState by mutableStateOf(ConversationDictationState.Idle)
        private set

    private val completionRevisions = mutableStateMapOf<ConversationDictationKey, Int>()
    private var nextSessionId = 0L
    private val notificationInstanceId = UUID.randomUUID().toString()
    private var nextRecognitionGenerationId = 0L
    private var activeRecognitionGenerationId: Long? = null
    private var recognitionSession: ConversationDictationRecognitionSession? = null
    private var generationTimeoutHandle: ConversationDictationTimeoutHandle? = null
    private var sessionTimeoutHandle: ConversationDictationTimeoutHandle? = null
    private var silenceTimeoutHandle: ConversationDictationTimeoutHandle? = null
    private var readinessHandle: ConversationDictationTimeoutHandle? = null
    private var readinessStartedAtMillis: Long? = null
    private var nextCallerAudioProbeId = 0L
    private var pendingCallerAudioProbeId: Long? = null
    private var microphoneHeld = false
    private var durableSession = false
    private var durableSessionReady by mutableStateOf(false)
    private var durableStartAccepted = false
    private var promotionReadyReceived = false
    private var promotionTimeoutHandle: ConversationDictationTimeoutHandle? = null
    private var validatingSessionId: Long? = null
    private var accumulatedTranscript = ""
    private var finishRequested by mutableStateOf(false)
    private var dispatchedSessionId by mutableStateOf<Long?>(null)
    private var sendJob: Job? = null
    private var requestedDeliveryMode: ConversationDictationDeliveryMode? = null
    private var generationHasSpeech = false
    private var consecutiveNoSpeechRestarts = 0
    private var generationReadyAtElapsedMillis: Long? = null
    private var restartTimeoutHandle: ConversationDictationTimeoutHandle? = null
    private var restartId = 0L
    private var providerDisconnectRetries = 0
    private var permissionRetryUsed = false
    private var playbackInterruptedForCapture = false

    // Entry points, recognition callbacks and timeout callbacks are main-thread confined.
    private var unresolvedRecognitionFailure: ConversationDictationFailure? = null
    private var silenceDeadlineElapsedMillis: Long? = null

    private val _permissionRequestId = mutableLongStateOf(0L)
    val permissionRequestId: Long
        get() = _permissionRequestId.longValue
    private var claimedPermissionRequestId = 0L

    private val _providerActivityRequestId = mutableLongStateOf(0L)
    val providerActivityRequestId: Long
        get() = _providerActivityRequestId.longValue

    val hasPendingSession: Boolean
        get() = state !is ConversationDictationState.Idle

    val blocksNewRequest: Boolean
        get() =
            state !is ConversationDictationState.Idle &&
                state !is ConversationDictationState.Failed

    val ownsMicrophone: Boolean
        get() = microphoneHeld

    val hasDurableSession: Boolean
        get() = durableSession

    /** Opaque process-and-session identity; delayed notification taps cannot target a later draft. */
    val notificationSessionToken: String?
        get() = state.sessionId?.let { "$notificationInstanceId:$it" }

    val deliveryInProgress: Boolean
        get() = dispatchedSessionId != null && dispatchedSessionId == state.sessionId

    /** The speech service package a provider failure's recovery action has to open. */
    val speechProviderPackage: String?
        get() = runCatching(platform::speechProviderPackage).getOrNull()

    val completionActionsEnabled: Boolean
        get() =
            !finishRequested &&
                !deliveryInProgress &&
                (
                    activeRecognitionGenerationId != null ||
                        state is ConversationDictationState.Starting
                )

    /** Returns the completion revision used by Compose consumers to observe a terminal write. */
    fun completionRevision(
        accountRef: String,
        groupIdHex: String,
    ): Int = completionRevisions[ConversationDictationKey.from(accountRef, groupIdHex)] ?: 0

    /** Whether the current immutable target belongs to the supplied conversation. */
    fun isOwnedBy(
        accountRef: String,
        groupIdHex: String,
    ): Boolean = state.target?.matchesConversation(accountRef, groupIdHex) == true

    /** Starts an app-owned, service-backed dictation session for the captured draft. */
    fun requestStart(
        accountRef: String,
        groupIdHex: String,
        draft: TextFieldValue,
    ): Boolean =
        requestStart(
            accountRef = accountRef,
            groupIdHex = groupIdHex,
            draft = draft,
            mode = ConversationDictationMode.InApp,
        )

    /** Starts the compatibility flow that delegates microphone ownership to a provider Activity. */
    fun requestProviderActivityStart(
        accountRef: String,
        groupIdHex: String,
        draft: TextFieldValue,
    ): Boolean =
        requestStart(
            accountRef = accountRef,
            groupIdHex = groupIdHex,
            draft = draft,
            mode = ConversationDictationMode.ProviderActivity,
        )

    /** Captures one immutable target and replaces any safely replaceable prior session. */
    private fun requestStart(
        accountRef: String,
        groupIdHex: String,
        draft: TextFieldValue,
        mode: ConversationDictationMode,
    ): Boolean {
        conversationDictationDiagnostic("event=request_start mode=${mode.name}")
        if (!targetAvailable(accountRef, groupIdHex)) return false
        // Unlike the app-owned SpeechRecognizer, an external provider Activity
        // cannot be synchronously terminated by this controller. Keep its one
        // ActivityResult owner stable until the provider returns, so a result
        // can never be misattributed to a replacement target.
        if (state is ConversationDictationState.ProviderActivityActive) return false
        if (blocksNewRequest && hasSameTarget(accountRef, groupIdHex, mode)) {
            return false
        }

        // A request for a different target/mode is an explicit replacement.
        // Tear down the previous generation before publishing the new target;
        // all callbacks from the previous generation then fail the session-id
        // ownership check and become no-ops.
        clearRecognitionSession(cancel = true)
        resetTranscriptSession()
        val sessionId = ++nextSessionId
        val capturedRevision = readDraft(accountRef, groupIdHex).revision
        val target =
            ConversationDictationTarget(
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                capturedDraft = draft.copy(composition = null),
                capturedDraftRevision = capturedRevision,
                mode = mode,
                finishAfterSilenceMillis = finishAfterSilenceMillis()?.takeIf { it > 0L },
                deliveryMode = deliveryMode(),
            )
        if (!disclosureAccepted()) {
            state = ConversationDictationState.DisclosureRequired(sessionId, target)
            return true
        }
        startTarget(sessionId, target)
        return true
    }

    /** Whether a duplicate request points at the current conversation and recognition mode. */
    private fun hasSameTarget(
        accountRef: String,
        groupIdHex: String,
        mode: ConversationDictationMode,
    ): Boolean =
        state.target?.let { target ->
            target.matchesConversation(accountRef, groupIdHex) &&
                target.mode == mode
        } == true

    /** Records the first-use disclosure and resumes its exact pending target. */
    fun acceptDisclosure() {
        val pending = state as? ConversationDictationState.DisclosureRequired ?: return
        markDisclosureAccepted()
        startTarget(pending.sessionId, pending.target)
    }

    /** Resumes recognition after a grant or publishes the appropriate permission failure. */
    fun onPermissionResult(
        granted: Boolean,
        permanentlyDenied: Boolean = false,
    ) {
        val pending = state as? ConversationDictationState.PermissionRequired ?: return
        if (!granted) {
            state =
                ConversationDictationState.Failed(
                    sessionId = pending.sessionId,
                    target = pending.target,
                    reason =
                        if (permanentlyDenied) {
                            ConversationDictationFailure.PermissionPermanentlyDenied
                        } else {
                            ConversationDictationFailure.PermissionDenied
                        },
                )
            return
        }
        when (platform.microphoneAccess()) {
            ConversationDictationMicrophoneAccess.Granted ->
                startWhenProviderAvailable(pending.sessionId, pending.target)
            ConversationDictationMicrophoneAccess.RuntimePermissionRequired ->
                fail(pending.sessionId, pending.target, ConversationDictationFailure.PermissionDenied)
            ConversationDictationMicrophoneAccess.AppOpDenied ->
                fail(pending.sessionId, pending.target, ConversationDictationFailure.PermissionPermanentlyDenied)
            ConversationDictationMicrophoneAccess.MicrophoneMuted ->
                fail(pending.sessionId, pending.target, ConversationDictationFailure.MicrophoneMuted)
        }
    }

    /** Claims one permission request so recomposition or Activity recreation cannot launch it twice. */
    fun beginPermissionRequest(requestId: Long): Boolean {
        if (requestId != permissionRequestId || requestId == claimedPermissionRequestId) return false
        if (state !is ConversationDictationState.PermissionRequired) return false
        claimedPermissionRequestId = requestId
        return true
    }

    /** Converts an Android permission-contract launch failure into a retryable terminal state. */
    fun onPermissionLaunchFailed(requestId: Long) {
        if (requestId != claimedPermissionRequestId) return
        val pending = state as? ConversationDictationState.PermissionRequired ?: return
        fail(pending.sessionId, pending.target, ConversationDictationFailure.Unknown)
    }

    /** Requests terminal provider output, or immediately commits segments already accumulated. */
    fun stop() = stopWithDeliveryMode(null)

    /** Stops recognition and pastes the result into the immutable origin draft. */
    fun paste() = stopWithDeliveryMode(ConversationDictationDeliveryMode.PasteIntoDraft)

    /** Stops recognition and sends only after the existing origin/draft safety checks pass. */
    fun send() = stopWithDeliveryMode(ConversationDictationDeliveryMode.SendOnFinish)

    /** Completes the owned session with its captured paste-or-send policy. */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun stopWithDeliveryMode(deliveryMode: ConversationDictationDeliveryMode?) {
        val current = state
        if (finishRequested) return
        if (
            current !is ConversationDictationState.Starting &&
            current !is ConversationDictationState.Listening &&
            current !is ConversationDictationState.Processing
        ) {
            return
        }
        if (platform.callerAudioHasPending()) {
            armCallerAudioDrainTimeout(requireNotNull(current.sessionId), requireNotNull(current.target))
        }
        requestedDeliveryMode = deliveryMode
        if (durableSession && !durableSessionReady) {
            finishRequested = true
            finalizeAccumulatedTranscript(requireNotNull(current.sessionId), requireNotNull(current.target))
            return
        }
        if (current is ConversationDictationState.Processing) {
            finishRequested = true
            silenceTimeoutHandle?.cancel()
            silenceTimeoutHandle = null
            val sessionId = current.sessionId ?: return
            val target = current.target ?: return
            val generationId = activeRecognitionGenerationId
            if (generationId == null) {
                sealAndDrainCallerAudio(sessionId, target)
            } else {
                runCatching {
                    recognitionSession?.stop {
                        if (owns(sessionId, generationId)) finishPlaybackInterruption()
                    }
                }.onFailure {
                    failOrRetainTranscript(sessionId, target, ConversationDictationFailure.Unknown)
                }
            }
            return
        }
        if (current !is ConversationDictationState.Starting && current !is ConversationDictationState.Listening) return
        val sessionId = current.sessionId ?: return
        val target = current.target ?: return
        finishRequested = true
        cancelPendingRestart()
        silenceTimeoutHandle?.cancel()
        silenceTimeoutHandle = null
        silenceDeadlineElapsedMillis = null
        val generationId = activeRecognitionGenerationId
        if (generationId == null) {
            sealAndDrainCallerAudio(sessionId, target)
            return
        }
        if (
            accumulatedTranscript.isNotBlank() &&
            !generationHasSpeech &&
            !platform.callerAudioHasPending()
        ) {
            clearRecognitionGeneration(
                cancel = true,
                onAudioCaptureFinished = ::finishPlaybackInterruption,
            )
            finalizeAccumulatedTranscript(sessionId, target)
            return
        }
        state = ConversationDictationState.Processing(sessionId, target)
        armGenerationTimeout(sessionId, generationId, PROCESSING_TIMEOUT_MILLIS) {
            failOrRetainTranscript(sessionId, target, ConversationDictationFailure.TimedOut)
        }
        runCatching {
            recognitionSession?.stop {
                if (owns(sessionId, generationId)) finishPlaybackInterruption()
            }
        }.onFailure { failOrRetainTranscript(sessionId, target, ConversationDictationFailure.Unknown) }
    }

    /** Seals a caller-owned partial chunk and starts one final provider generation to drain it. */
    private fun sealAndDrainCallerAudio(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        state = ConversationDictationState.Processing(sessionId, target)
        armCallerAudioDrainTimeout(sessionId, target)
        val platformOwnsClosure =
            runCatching {
                platform.finishCallerAudioCapture {
                    if (state !is ConversationDictationState.Processing || state.sessionId != sessionId) {
                        return@finishCallerAudioCapture
                    }
                    if (platform.callerAudioHasPending()) {
                        startRecognition(sessionId, target)
                    } else {
                        finalizeAccumulatedTranscript(sessionId, target)
                    }
                }
            }.getOrDefault(false)
        if (!platformOwnsClosure) finalizeAccumulatedTranscript(sessionId, target)
    }

    /** Keeps one drain deadline active while replacement recognizers move through their states. */
    private fun armCallerAudioDrainTimeout(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        armSessionTimeout(sessionId, CALLER_AUDIO_DRAIN_TIMEOUT_MILLIS) {
            if (finishRequested && state.sessionId == sessionId) {
                failOrRetainTranscript(sessionId, target, ConversationDictationFailure.TimedOut)
            }
        }
    }

    /** Discards process-memory transcript state and releases every resource held by the session. */
    fun cancel() {
        // Once MDK dispatch starts, cancellation cannot recall the message. Retain ownership until its outcome.
        if (deliveryInProgress) return
        cancelSession()
    }

    private fun cancelSession() {
        if (state is ConversationDictationState.CheckingProvider) {
            emitReadiness(ConversationDictationReadinessPhase.Cancelled)
        }
        clearRecognitionSession(cancel = true)
        resetTranscriptSession()
        state = ConversationDictationState.Idle
    }

    /** Claims the queued provider-Activity request exactly once before Android launches it. */
    fun beginProviderActivityLaunch(requestId: Long): Boolean {
        if (requestId != providerActivityRequestId) {
            conversationDictationDiagnostic("event=provider_activity_launch_claimed claimed=false reason=stale_request")
            return false
        }
        val pending = state as? ConversationDictationState.ProviderActivityRequired
        if (pending == null) {
            conversationDictationDiagnostic("event=provider_activity_launch_claimed claimed=false reason=state")
            return false
        }
        emitReadiness(ConversationDictationReadinessPhase.LaunchingProvider)
        state = ConversationDictationState.ProviderActivityActive(pending.sessionId, pending.target)
        conversationDictationDiagnostic("event=provider_activity_launch_claimed claimed=true")
        return true
    }

    /** Validates and delivers the transcript returned by the provider-owned Activity. */
    fun onProviderActivityResult(transcript: String?) {
        conversationDictationDiagnostic("event=provider_activity_result has_text=${!transcript.isNullOrBlank()}")
        val active = state as? ConversationDictationState.ProviderActivityActive ?: return
        val recognized = transcript?.trim().orEmpty()
        if (recognized.isBlank()) {
            fail(active.sessionId, active.target, ConversationDictationFailure.NoSpeech)
            return
        }
        if (!targetAvailable(active.target.accountRef, active.target.groupIdHex)) {
            cancel()
            return
        }
        validateAndDeliverTranscript(active.sessionId, active.target, recognized)
    }

    /** Clears ownership after the user dismisses the provider-owned Activity. */
    fun onProviderActivityCancelled() {
        conversationDictationDiagnostic("event=provider_activity_cancelled")
        if (state is ConversationDictationState.ProviderActivityActive) cancel()
    }

    /** Converts a provider-Activity launch failure into a retryable terminal state. */
    fun onProviderActivityLaunchFailed() {
        conversationDictationDiagnostic("event=provider_activity_launch_failed")
        val providerState = state as? ConversationDictationState.ProviderActivityActive ?: return
        fail(
            providerState.sessionId,
            providerState.target,
            ConversationDictationFailure.ProviderUnavailable,
        )
    }

    /** Dismisses a terminal failure without retrying or changing any draft. */
    fun dismissFailure() {
        if (state is ConversationDictationState.Failed) state = ConversationDictationState.Idle
    }

    /** Recreates a failed session against the origin's current authoritative draft. */
    fun retry() {
        val failed = state as? ConversationDictationState.Failed ?: return
        requestStart(
            accountRef = failed.target.accountRef,
            groupIdHex = failed.target.groupIdHex,
            draft = readDraft(failed.target.accountRef, failed.target.groupIdHex).value,
            mode = failed.target.mode,
        )
    }

    /** Revalidates the origin and appends a conflicted transcript at the current draft end. */
    fun insertReviewAtEnd() {
        val review = state as? ConversationDictationState.ReviewRequired ?: return
        if (!targetAvailable(review.target.accountRef, review.target.groupIdHex)) {
            cancel()
            return
        }
        val validator = targetValidator
        val validationScope = targetValidationScope
        if (validator == null || validationScope == null) {
            insertReviewAtEndValidated(review)
            return
        }
        if (validatingSessionId == review.sessionId) return
        validatingSessionId = review.sessionId
        validationScope.launch {
            val available =
                try {
                    validateTargetAuthoritatively(validator, review.target)
                } finally {
                    if (validatingSessionId == review.sessionId) validatingSessionId = null
                }
            val current = state as? ConversationDictationState.ReviewRequired
            if (current?.sessionId != review.sessionId) return@launch
            if (!available || !targetAvailable(review.target.accountRef, review.target.groupIdHex)) {
                cancel()
                return@launch
            }
            insertReviewAtEndValidated(review)
        }
    }

    /** Applies a reviewed transcript with bounded optimistic retries. */
    private fun insertReviewAtEndValidated(review: ConversationDictationState.ReviewRequired) {
        repeat(MAX_CONDITIONAL_WRITE_ATTEMPTS) {
            val current = readDraft(review.target.accountRef, review.target.groupIdHex)
            val merged = appendConversationDictationTranscript(current.value, review.transcript)
            if (
                writeDraft(
                    review.target.accountRef,
                    review.target.groupIdHex,
                    current.revision,
                    merged,
                )
            ) {
                complete(review.target)
                return
            }
        }
    }

    /** Explicitly discards transcript text retained for conflict review. */
    fun dismissReview() {
        if (state is ConversationDictationState.ReviewRequired) state = ConversationDictationState.Idle
    }

    fun dismissDeliveryUnknown() {
        if (state is ConversationDictationState.DeliveryUnknown) state = ConversationDictationState.Idle
    }

    /** Cancels the session if its exact origin conversation was removed. */
    fun onTargetRemoved(
        accountRef: String,
        groupIdHex: String,
    ) {
        if (isOwnedBy(accountRef, groupIdHex)) cancelSession()
    }

    /** Cancels only when the target account is signed out or removed, not when it becomes inactive. */
    fun onAccountUnavailable(accountRef: String) {
        if (state.target?.accountRef?.equals(accountRef, ignoreCase = true) == true) cancelSession()
    }

    /** Releases any provider/microphone resource without discarding terminal review text. */
    fun onAppBackgrounded() {
        when (state) {
            is ConversationDictationState.DisclosureRequired,
            is ConversationDictationState.PermissionRequired,
            is ConversationDictationState.CheckingProvider,
            -> cancel()
            is ConversationDictationState.Starting,
            is ConversationDictationState.Listening,
            is ConversationDictationState.Processing,
            -> if (!durableSession) cancel()
            // Launching the provider Activity necessarily backgrounds White
            // Noise. Its registered ActivityResult callback remains the owner
            // across that transition and across Activity recreation.
            is ConversationDictationState.ProviderActivityRequired,
            is ConversationDictationState.ProviderActivityActive,
            -> Unit
            else -> Unit
        }
    }

    /** Keeps service-backed capture alive when the UI task is removed from recents. */
    fun onTaskRemoved() {
        if (!durableSession) cancel()
    }

    /** Cancels capture if Android destroys the service that makes background ownership explicit. */
    fun onDurableServiceDestroyed(sessionToken: String) {
        if (!durableSession || notificationSessionToken != sessionToken) return
        durableSession = false
        cancel()
    }

    /** Starts capture only after this session's foreground promotion and enqueue are both confirmed. */
    @Suppress(
        "ComplexCondition",
        "CyclomaticComplexMethod",
        "ReturnCount",
    )
    fun onDurableServiceReady(sessionToken: String) {
        val current = state as? ConversationDictationState.Starting ?: return
        if (
            !durableSession ||
            durableSessionReady ||
            notificationSessionToken != sessionToken ||
            finishRequested
        ) {
            return
        }
        promotionReadyReceived = true
        if (!durableStartAccepted) return
        try {
            if (!targetAvailable(current.target.accountRef, current.target.groupIdHex)) {
                if (ownsDurableSession(current.sessionId, sessionToken)) cancel()
                return
            }
            when (platform.microphoneAccess()) {
                ConversationDictationMicrophoneAccess.Granted -> {
                    if (!platform.recognitionAvailable()) {
                        fail(current.sessionId, current.target, ConversationDictationFailure.ProviderUnavailable)
                        return
                    }
                }
                ConversationDictationMicrophoneAccess.RuntimePermissionRequired -> {
                    fail(current.sessionId, current.target, ConversationDictationFailure.PermissionDenied)
                    return
                }
                ConversationDictationMicrophoneAccess.AppOpDenied -> {
                    fail(current.sessionId, current.target, ConversationDictationFailure.PermissionPermanentlyDenied)
                    return
                }
                ConversationDictationMicrophoneAccess.MicrophoneMuted -> {
                    fail(current.sessionId, current.target, ConversationDictationFailure.MicrophoneMuted)
                    return
                }
            }
        } catch (_: RuntimeException) {
            if (ownsDurableSession(current.sessionId, sessionToken)) {
                fail(current.sessionId, current.target, ConversationDictationFailure.Unknown)
            }
            return
        }
        if (!ownsDurableSession(current.sessionId, sessionToken) || finishRequested) return
        promotionTimeoutHandle?.cancel()
        promotionTimeoutHandle = null
        durableSessionReady = true
        startRecognition(current.sessionId, current.target)
    }

    private fun ownsDurableSession(
        sessionId: Long,
        sessionToken: String,
    ): Boolean =
        durableSession &&
            state.sessionId == sessionId &&
            notificationSessionToken == sessionToken

    /** A rejected promotion is recoverable but must not silently open another recording surface. */
    fun onDurableServiceStartFailed(sessionToken: String) {
        val current = state
        if (!durableSession || notificationSessionToken != sessionToken) return
        fail(requireNotNull(current.sessionId), requireNotNull(current.target), ConversationDictationFailure.Unknown)
    }

    /** Routes a captured target to either app-owned recognition or provider compatibility UI. */
    private fun startTarget(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        conversationDictationDiagnostic("event=start_target mode=${target.mode.name}")
        when (target.mode) {
            ConversationDictationMode.InApp -> startInAppOrDivertToProvider(sessionId, target)
            ConversationDictationMode.ProviderActivity -> prepareProviderActivity(sessionId, target)
        }
    }

    /**
     * Chooses the one surface this gesture will show.
     *
     * An unprivileged provider can only serve an app-owned session when it transcribes
     * the audio White Noise captures. Deciding that here, from what is already known about the
     * installed provider build, keeps one gesture to one UI: the in-app controls never hand an
     * already-rejected session to the provider's own recognition Activity mid-gesture.
     */
    private fun startInAppOrDivertToProvider(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        // A platform that cannot answer is treated as one that never needed caller audio, which is
        // the path every configuration used before this routing existed.
        val requirement =
            runCatching(platform::callerAudioRequirement)
                .getOrDefault(ConversationDictationCallerAudioRequirement.NotNeeded)
        conversationDictationDiagnostic("event=caller_audio_requirement requirement=${requirement.name}")
        if (requirement == ConversationDictationCallerAudioRequirement.Unknown) {
            probeCallerAudioSupport(sessionId, target)
        } else {
            routeByCallerAudioRequirement(sessionId, target, requirement)
        }
    }

    /** Sends this session to app-owned capture, or to the only surface that can still capture. */
    private fun routeByCallerAudioRequirement(
        sessionId: Long,
        target: ConversationDictationTarget,
        requirement: ConversationDictationCallerAudioRequirement,
    ) {
        if (requirement.allowsInAppCapture) {
            startOrRequestPermission(sessionId, target)
            return
        }
        // The provider ignores caller-supplied audio and cannot open the microphone for an
        // app-owned session, so its own recognition UI is the only surface that can capture.
        prepareProviderActivity(
            sessionId,
            target.copy(mode = ConversationDictationMode.ProviderActivity),
        )
    }

    /**
     * Asks the resolved provider once, without opening the microphone, and routes this exact
     * session on the answer. [ConversationDictationState.CheckingProvider] already renders as the
     * bounded readiness phase, so the wait needs no new surface.
     */
    private fun probeCallerAudioSupport(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        val probeId = ++nextCallerAudioProbeId
        pendingCallerAudioProbeId = probeId
        val startedAt = elapsedRealtime()
        readinessStartedAtMillis = startedAt
        state = ConversationDictationState.CheckingProvider(sessionId, target, startedAt)
        emitReadiness(ConversationDictationReadinessPhase.CheckingService)
        conversationDictationDiagnostic("event=caller_audio_probe_start")
        armSessionTimeout(sessionId, CALLER_AUDIO_PROBE_TIMEOUT_MILLIS) {
            resolveCallerAudioProbe(
                probeId,
                sessionId,
                target,
                ConversationDictationCallerAudioRequirement.Unknown,
            )
        }
        val handle =
            runCatching {
                platform.probeCallerAudioSupport { requirement ->
                    resolveCallerAudioProbe(probeId, sessionId, target, requirement)
                }
            }.getOrElse { failure ->
                if (failure !is RuntimeException) throw failure
                // A platform that cannot even start the question has established nothing about the
                // descriptor, so this session resolves as unknown rather than failing the gesture.
                conversationDictationDiagnostic(
                    "event=caller_audio_probe_start_failed type=${failure.javaClass.simpleName}",
                )
                resolveCallerAudioProbe(
                    probeId,
                    sessionId,
                    target,
                    ConversationDictationCallerAudioRequirement.Unknown,
                )
                ConversationDictationTimeoutHandle {}
            }
        // A synchronous answer has already routed this session, and that routing can own a
        // readiness handle of its own, so only a still-pending probe adopts this one.
        if (pendingCallerAudioProbeId == probeId) readinessHandle = handle else handle.cancel()
    }

    /**
     * Applies the first answer a probe produces and discards every later one.
     *
     * An answer that establishes nothing about caller-supplied audio keeps app-owned capture, where
     * the real failure stays visible, instead of sending the user into provider UI whose engine
     * would fail the same way.
     */
    private fun resolveCallerAudioProbe(
        probeId: Long,
        sessionId: Long,
        target: ConversationDictationTarget,
        requirement: ConversationDictationCallerAudioRequirement,
    ) {
        if (pendingCallerAudioProbeId != probeId) return
        val checking = state as? ConversationDictationState.CheckingProvider
        if (checking?.sessionId != sessionId) return
        pendingCallerAudioProbeId = null
        sessionTimeoutHandle?.cancel()
        sessionTimeoutHandle = null
        readinessHandle?.cancel()
        readinessHandle = null
        conversationDictationDiagnostic("event=caller_audio_probe_result requirement=${requirement.name}")
        // A divert publishes its own readiness sequence from the provider-Activity check.
        if (requirement.allowsInAppCapture) emitReadiness(ConversationDictationReadinessPhase.ServiceReady)
        routeByCallerAudioRequirement(sessionId, target, requirement)
    }

    /** Performs the bounded microphone-free readiness check before provider UI launch. */
    private fun prepareProviderActivity(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        conversationDictationDiagnostic("event=provider_activity_check_start")
        val startedAt = elapsedRealtime()
        readinessStartedAtMillis = startedAt
        state = ConversationDictationState.CheckingProvider(sessionId, target, startedAt)
        emitReadiness(ConversationDictationReadinessPhase.CheckingService)
        armSessionTimeout(sessionId, PROVIDER_READINESS_TIMEOUT_MILLIS) {
            emitReadiness(ConversationDictationReadinessPhase.TimedOut)
            fail(sessionId, target, ConversationDictationFailure.TimedOut)
        }
        val handle =
            platform.checkRecognitionActivity { available ->
                conversationDictationDiagnostic("event=provider_activity_check_result available=$available")
                val checking = state as? ConversationDictationState.CheckingProvider
                if (checking?.sessionId != sessionId) return@checkRecognitionActivity
                sessionTimeoutHandle?.cancel()
                sessionTimeoutHandle = null
                readinessHandle = null
                if (!available) {
                    emitReadiness(ConversationDictationReadinessPhase.ProviderUnavailable)
                    fail(sessionId, target, ConversationDictationFailure.ProviderUnavailable)
                    return@checkRecognitionActivity
                }
                emitReadiness(ConversationDictationReadinessPhase.ServiceReady)
                state = ConversationDictationState.ProviderActivityRequired(sessionId, target)
                _providerActivityRequestId.longValue += 1L
            }
        if (state is ConversationDictationState.CheckingProvider) {
            readinessHandle = handle
        } else {
            handle.cancel()
        }
    }

    /** Checks provider and White Noise's runtime grant before creating a recognizer generation. */
    private fun startOrRequestPermission(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        state = ConversationDictationState.Starting(sessionId, target)
        val configured = platform.recognitionConfigured()
        conversationDictationDiagnostic("event=recognition_configured configured=$configured")
        val microphoneAccess = platform.microphoneAccess()
        conversationDictationDiagnostic("event=microphone_preflight access=${microphoneAccess.name}")
        // Routing has already chosen this gesture's capture surface. A failed in-app session
        // stays here with recovery; it never opens a second recording UI mid-gesture.
        if (!configured) {
            val failure =
                when (microphoneAccess) {
                    ConversationDictationMicrophoneAccess.AppOpDenied ->
                        ConversationDictationFailure.PermissionPermanentlyDenied
                    ConversationDictationMicrophoneAccess.MicrophoneMuted ->
                        ConversationDictationFailure.MicrophoneMuted
                    ConversationDictationMicrophoneAccess.Granted,
                    ConversationDictationMicrophoneAccess.RuntimePermissionRequired,
                    -> ConversationDictationFailure.ProviderUnavailable
                }
            fail(sessionId, target, failure)
            return
        }
        when (microphoneAccess) {
            ConversationDictationMicrophoneAccess.Granted -> startWhenProviderAvailable(sessionId, target)
            ConversationDictationMicrophoneAccess.RuntimePermissionRequired -> {
                state = ConversationDictationState.PermissionRequired(sessionId, target)
                _permissionRequestId.longValue += 1L
            }
            ConversationDictationMicrophoneAccess.AppOpDenied ->
                fail(sessionId, target, ConversationDictationFailure.PermissionPermanentlyDenied)
            ConversationDictationMicrophoneAccess.MicrophoneMuted ->
                fail(sessionId, target, ConversationDictationFailure.MicrophoneMuted)
        }
    }

    /** Re-checks the selected provider after permission is known before opening the microphone. */
    private fun startWhenProviderAvailable(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        state = ConversationDictationState.Starting(sessionId, target)
        val available = platform.recognitionAvailable()
        conversationDictationDiagnostic("event=recognition_available available=$available")
        if (!available) {
            fail(sessionId, target, ConversationDictationFailure.ProviderUnavailable)
            return
        }
        startRecognition(sessionId, target)
    }

    /** Publishes pending ownership before enqueueing, without treating enqueue success as promotion. */
    private fun ensureDurableSession(
        sessionId: Long,
        target: ConversationDictationTarget,
    ): Boolean {
        if (durableSession) return durableSessionReady
        durableSession = true
        durableSessionReady = false
        durableStartAccepted = false
        promotionReadyReceived = false
        val token = requireNotNull(notificationSessionToken)
        promotionTimeoutHandle =
            scheduleTimeout(FOREGROUND_READINESS_TIMEOUT_MILLIS) {
                if (durableSession && !durableSessionReady && notificationSessionToken == token) {
                    fail(sessionId, target, ConversationDictationFailure.TimedOut)
                }
            }
        val started = runCatching { startDurableSession(token) { onDurableServiceReady(token) } }.getOrDefault(false)
        conversationDictationDiagnostic("event=foreground_service_start requested=$started")
        if (durableSession && notificationSessionToken == token) {
            if (started) {
                durableStartAccepted = true
                if (promotionReadyReceived) onDurableServiceReady(token)
            } else {
                fail(sessionId, target, ConversationDictationFailure.Unknown)
            }
        }
        // The acknowledgement path owns continuation, including a synchronous test/platform callback.
        return false
    }

    /** Starts one bounded recognizer generation while retaining logical-session ownership. */
    private fun startRecognition(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        if (state.sessionId != sessionId) return
        clearRecognitionGeneration(cancel = false)
        if (!ensureDurableSession(sessionId, target)) return
        // Drain generations use sealed PCM; reacquiring capture would replace their drain deadline.
        if (!microphoneHeld && !finishRequested) {
            val acquired = tryAcquireMicrophone()
            conversationDictationDiagnostic("event=microphone_lease acquired=$acquired")
            if (!acquired) {
                fail(sessionId, target, ConversationDictationFailure.MicrophoneInUse)
                return
            }
            microphoneHeld = true
            playbackInterruptedForCapture = true
            if (runCatching(onBeforeRecognition).isFailure) {
                fail(sessionId, target, ConversationDictationFailure.Unknown)
                return
            }
            armSessionTimeout(sessionId, MAX_SESSION_MILLIS) {
                when (state) {
                    is ConversationDictationState.Starting,
                    is ConversationDictationState.Listening,
                    is ConversationDictationState.Processing,
                    -> stop()
                    else -> failOrRetainTranscript(sessionId, target, ConversationDictationFailure.TimedOut)
                }
            }
        }
        val generationId = ++nextRecognitionGenerationId
        conversationDictationDiagnostic("event=recognizer_generation_start generation=$generationId")
        activeRecognitionGenerationId = generationId
        generationHasSpeech = false
        state = ConversationDictationState.Starting(sessionId, target)
        armGenerationTimeout(sessionId, generationId, STARTING_TIMEOUT_MILLIS) {
            conversationDictationDiagnostic("event=recognizer_start_timeout generation=$generationId")
            failOrRetainTranscript(sessionId, target, ConversationDictationFailure.TimedOut)
        }
        val listener =
            object : ConversationDictationRecognitionListener {
                /** Promotes only the current generation from starting to listening. */
                override fun onReady() {
                    conversationDictationDiagnostic("event=callback_ready generation=$generationId")
                    if (!owns(sessionId, generationId) || state !is ConversationDictationState.Starting) return
                    unresolvedRecognitionFailure = null
                    generationTimeoutHandle?.cancel()
                    generationTimeoutHandle = null
                    generationReadyAtElapsedMillis = elapsedRealtime()
                    state =
                        ConversationDictationState.Listening(
                            sessionId = sessionId,
                            target = target,
                            startedAtElapsedMillis = requireNotNull(generationReadyAtElapsedMillis),
                        )
                }

                /** Records speech only for the generation that still owns the session. */
                override fun onBeginningOfSpeech() {
                    conversationDictationDiagnostic("event=callback_beginning_of_speech generation=$generationId")
                    if (!owns(sessionId, generationId)) return
                    generationHasSpeech = true
                    unresolvedRecognitionFailure = null
                    silenceTimeoutHandle?.cancel()
                    silenceTimeoutHandle = null
                    silenceDeadlineElapsedMillis = null
                }

                /** Moves the owned generation into bounded final-result processing. */
                override fun onEndOfSpeech() {
                    conversationDictationDiagnostic("event=callback_end_of_speech generation=$generationId")
                    if (!owns(sessionId, generationId)) return
                    when {
                        state is ConversationDictationState.Starting ||
                            state is ConversationDictationState.Listening -> {
                            state = ConversationDictationState.Processing(sessionId, target)
                            armGenerationTimeout(sessionId, generationId, PROCESSING_TIMEOUT_MILLIS) {
                                failOrRetainTranscript(sessionId, target, ConversationDictationFailure.TimedOut)
                            }
                        }
                    }
                }

                /** Commits an owned final segment and either finishes or schedules the next generation. */
                override fun onResult(transcript: String?) {
                    conversationDictationDiagnostic(
                        "event=callback_result generation=$generationId has_text=${!transcript.isNullOrBlank()}",
                    )
                    if (!owns(sessionId, generationId)) return
                    val readyAt = generationReadyAtElapsedMillis
                    val recognized = transcript?.trim().orEmpty()
                    recognitionSession?.acknowledgeCallerAudio()
                    if (recognized.isNotBlank()) unresolvedRecognitionFailure = null
                    if (recognized.isBlank()) {
                        clearRecognitionGeneration(cancel = false)
                        if (finishRequested) {
                            continueOrFinalizeCallerAudioDrain(sessionId, target)
                        } else {
                            restartAfterNoSpeech(sessionId, target, readyAt)
                        }
                        return
                    }
                    val targetStillAvailable =
                        runCatching { targetAvailable(target.accountRef, target.groupIdHex) }.getOrDefault(false)
                    if (!targetStillAvailable) {
                        if (state.sessionId == sessionId) cancel()
                        return
                    }
                    clearRecognitionGeneration(cancel = false)
                    commitSegment(recognized)
                    if (finishRequested) {
                        continueOrFinalizeCallerAudioDrain(sessionId, target)
                    } else {
                        scheduleRestart(sessionId, target, SUCCESS_RESULT_RESTART_DELAY_MILLIS, "result")
                    }
                }

                /** Applies retry or terminal-failure policy only to the current generation. */
                override fun onError(error: ConversationDictationFailure) {
                    conversationDictationDiagnostic(
                        "event=callback_error generation=$generationId failure=${error.name}",
                    )
                    if (!owns(sessionId, generationId)) return
                    val readyAt = generationReadyAtElapsedMillis
                    val failure =
                        if (error == ConversationDictationFailure.PermissionDenied) {
                            recognitionPermissionFailure()
                        } else if (error == ConversationDictationFailure.NoSpeech) {
                            unresolvedRecognitionFailure ?: error
                        } else {
                            error
                        }
                    if (!owns(sessionId, generationId)) return
                    unresolvedRecognitionFailure = failure
                    clearRecognitionGeneration(cancel = false)
                    when {
                        finishRequested && accumulatedTranscript.isNotBlank() && !failure.requiresTranscriptReview ->
                            finalizeAccumulatedTranscript(sessionId, target)
                        finishRequested -> failOrRetainTranscript(sessionId, target, failure)
                        error == ConversationDictationFailure.NoSpeech ->
                            restartAfterNoSpeech(sessionId, target, readyAt)
                        error == ConversationDictationFailure.PermissionDenied ->
                            recoverPermissionFailure(sessionId, target)
                        error == ConversationDictationFailure.ProviderDisconnected ->
                            recoverProviderDisconnect(sessionId, target)
                        accumulatedTranscript.isNotBlank() -> retainAccumulatedTranscriptForReview(sessionId, target)
                        else -> fail(sessionId, target, failure)
                    }
                }
            }
        runCatching {
            platform.createSession(listener).also { recognitionSession = it }.start()
        }.onFailure { error ->
            conversationDictationDiagnostic("event=recognizer_start_exception type=${error.javaClass.simpleName}")
            if (accumulatedTranscript.isNotBlank()) {
                retainAccumulatedTranscriptForReview(sessionId, target)
            } else {
                fail(
                    sessionId,
                    target,
                    if (error is ConversationDictationProviderUnavailableException) {
                        ConversationDictationFailure.ProviderUnavailable
                    } else {
                        ConversationDictationFailure.Unknown
                    },
                )
            }
        }
    }

    /** Keeps the logical session fenced while every captured chunk is recognized exactly once. */
    private fun continueOrFinalizeCallerAudioDrain(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        if (runCatching(platform::callerAudioHasPending).getOrDefault(false)) {
            startRecognition(sessionId, target)
        } else {
            finalizeAccumulatedTranscript(sessionId, target)
        }
    }

    /** Keeps ordinary manual silence alive while bounding broken rapid empty generations. */
    private fun restartAfterNoSpeech(
        sessionId: Long,
        target: ConversationDictationTarget,
        readyAtElapsedMillis: Long?,
    ) {
        val readyDurationMillis = readyAtElapsedMillis?.let { elapsedRealtime() - it }
        val ordinarySilence = readyDurationMillis != null && readyDurationMillis >= ORDINARY_SILENCE_MILLIS
        if (ordinarySilence) {
            consecutiveNoSpeechRestarts = 0
        } else {
            consecutiveNoSpeechRestarts += 1
            if (consecutiveNoSpeechRestarts >= MAX_CONSECUTIVE_RAPID_EMPTY_GENERATIONS) {
                failOrRetainTranscript(
                    sessionId,
                    target,
                    unresolvedRecognitionFailure ?: ConversationDictationFailure.NoSpeech,
                )
                return
            }
        }
        scheduleRestart(sessionId, target, GENERATION_RESTART_DELAY_MILLIS, "no_speech")
    }

    /** Retries one code-9 denial only after fresh native-mode safety checks. */
    @Suppress("CyclomaticComplexMethod") // Keep retry fencing and failure mapping in one decision.
    private fun recoverPermissionFailure(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        val targetStillAvailable =
            try {
                targetAvailable(target.accountRef, target.groupIdHex)
            } catch (_: RuntimeException) {
                if (state.sessionId == sessionId) {
                    failOrRetainTranscript(sessionId, target, ConversationDictationFailure.Unknown)
                }
                return
            }
        if (state.sessionId != sessionId || finishRequested) return
        if (!targetStillAvailable) {
            cancel()
            return
        }
        val retryFailure =
            try {
                when {
                    !platform.recognitionAvailable() -> ConversationDictationFailure.ProviderUnavailable
                    else ->
                        recognitionPermissionFailure().takeUnless {
                            it == ConversationDictationFailure.ProviderAccessRejected
                        }
                }
            } catch (_: RuntimeException) {
                ConversationDictationFailure.Unknown
            }
        if (state.sessionId != sessionId || finishRequested) return
        if (retryFailure != null) {
            failOrRetainTranscript(sessionId, target, retryFailure)
            return
        }
        unresolvedRecognitionFailure = ConversationDictationFailure.ProviderAccessRejected
        if (permissionRetryUsed) {
            // A service that keeps rejecting an otherwise granted caller cannot serve app-owned
            // capture. Report it here instead of opening the provider's own recognition UI; the
            // failure action offers voice-input settings, which is where a selectable service is
            // chosen, and the composer keeps its own controls throughout.
            failOrRetainTranscript(sessionId, target, ConversationDictationFailure.ProviderAccessRejected)
            return
        }
        permissionRetryUsed = true
        scheduleRestart(sessionId, target, PERMISSION_RETRY_DELAY_MILLIS, "permission")
    }

    /** A provider's code 9 does not mean White Noise lacks its own microphone grant. */
    private fun recognitionPermissionFailure(): ConversationDictationFailure =
        try {
            when (platform.microphoneAccess()) {
                ConversationDictationMicrophoneAccess.Granted -> ConversationDictationFailure.ProviderAccessRejected
                ConversationDictationMicrophoneAccess.RuntimePermissionRequired ->
                    ConversationDictationFailure.PermissionDenied
                ConversationDictationMicrophoneAccess.AppOpDenied ->
                    ConversationDictationFailure.PermissionPermanentlyDenied
                ConversationDictationMicrophoneAccess.MicrophoneMuted -> ConversationDictationFailure.MicrophoneMuted
            }
        } catch (_: RuntimeException) {
            ConversationDictationFailure.Unknown
        }

    /** Retries provider disconnects with one logical-session-wide bounded backoff budget. */
    private fun recoverProviderDisconnect(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        if (providerDisconnectRetries >= PROVIDER_DISCONNECT_RETRY_DELAYS_MILLIS.size) {
            failOrRetainTranscript(sessionId, target, ConversationDictationFailure.ProviderDisconnected)
            return
        }
        val delayMillis = PROVIDER_DISCONNECT_RETRY_DELAYS_MILLIS[providerDisconnectRetries]
        providerDisconnectRetries += 1
        scheduleRestart(sessionId, target, delayMillis, "provider_disconnected")
    }

    /** Opens a fresh provider generation after a fenced, cancellable delay. */
    @Suppress("ComplexCondition") // All four predicates fence one immutable scheduled restart.
    private fun scheduleRestart(
        sessionId: Long,
        target: ConversationDictationTarget,
        delayMillis: Long,
        reason: String,
    ) {
        if (state.sessionId != sessionId || finishRequested) return
        cancelPendingRestart()
        state = ConversationDictationState.Starting(sessionId, target)
        val scheduledRestartId = ++restartId
        conversationDictationDiagnostic(
            "event=recognizer_restart_scheduled reason=$reason delay_ms=$delayMillis restart=$scheduledRestartId",
        )
        armSilenceDeadline(sessionId, target)
        restartTimeoutHandle =
            scheduleTimeout(delayMillis) {
                if (
                    state.sessionId == sessionId &&
                    state is ConversationDictationState.Starting &&
                    !finishRequested &&
                    restartId == scheduledRestartId
                ) {
                    restartTimeoutHandle = null
                    resumeRecognitionAfterDelay(sessionId, target)
                }
            }
    }

    /** Rechecks every revocable native precondition after the delay and before reacquiring capture. */
    @Suppress("CyclomaticComplexMethod") // Keep every revocable precondition in one fail-closed boundary.
    private fun resumeRecognitionAfterDelay(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        val targetStillAvailable =
            try {
                targetAvailable(target.accountRef, target.groupIdHex)
            } catch (_: RuntimeException) {
                if (state.sessionId == sessionId) {
                    failOrRetainTranscript(sessionId, target, ConversationDictationFailure.Unknown)
                }
                return
            }
        if (state.sessionId != sessionId || finishRequested) return
        if (!targetStillAvailable) {
            cancel()
            return
        }
        val failure =
            try {
                when {
                    !platform.recognitionAvailable() -> ConversationDictationFailure.ProviderUnavailable
                    else ->
                        when (platform.microphoneAccess()) {
                            ConversationDictationMicrophoneAccess.Granted -> null
                            ConversationDictationMicrophoneAccess.RuntimePermissionRequired ->
                                ConversationDictationFailure.PermissionDenied
                            ConversationDictationMicrophoneAccess.AppOpDenied ->
                                ConversationDictationFailure.PermissionPermanentlyDenied
                            ConversationDictationMicrophoneAccess.MicrophoneMuted ->
                                ConversationDictationFailure.MicrophoneMuted
                        }
                }
            } catch (_: RuntimeException) {
                ConversationDictationFailure.Unknown
            }
        if (state.sessionId != sessionId || finishRequested) return
        if (failure != null) {
            failOrRetainTranscript(sessionId, target, failure)
            return
        }
        startRecognition(sessionId, target)
    }

    private fun cancelPendingRestart() {
        restartTimeoutHandle?.cancel()
        restartTimeoutHandle = null
        restartId += 1L
    }

    /** Preserves an already armed silence deadline across delayed provider recovery. */
    private fun armSilenceDeadline(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        val silenceMillis = target.finishAfterSilenceMillis ?: return
        if (accumulatedTranscript.isBlank()) return
        val now = elapsedRealtime()
        val deadline = silenceDeadlineElapsedMillis ?: (now + silenceMillis).also { silenceDeadlineElapsedMillis = it }
        val remainingMillis = (deadline - now).coerceAtLeast(0L)
        silenceTimeoutHandle?.cancel()
        silenceTimeoutHandle =
            scheduleTimeout(remainingMillis) {
                val capturedSilence = platform.callerAudioSilenceMillis()
                if (state.sessionId == sessionId && capturedSilence != null) {
                    finishAfterCallerAudioSilence(sessionId, target, silenceMillis, capturedSilence)
                } else if (state.sessionId == sessionId && !generationHasSpeech && accumulatedTranscript.isNotBlank()) {
                    finishRequested = true
                    cancelPendingRestart()
                    clearRecognitionGeneration(
                        cancel = true,
                        onAudioCaptureFinished = ::finishPlaybackInterruption,
                    )
                    finalizeAccumulatedTranscript(sessionId, target)
                }
            }
    }

    /** Rechecks actual microphone activity and drains the tail before automatic completion. */
    private fun finishAfterCallerAudioSilence(
        sessionId: Long,
        target: ConversationDictationTarget,
        thresholdMillis: Long,
        capturedSilenceMillis: Long,
    ) {
        if (capturedSilenceMillis < thresholdMillis) {
            silenceDeadlineElapsedMillis = elapsedRealtime() + thresholdMillis - capturedSilenceMillis
            armSilenceDeadline(sessionId, target)
        } else {
            stop()
        }
    }

    /** Appends one provider-final segment; generation ownership rejects duplicate callbacks. */
    private fun commitSegment(segment: String) {
        val normalized = segment.trim()
        if (normalized.isBlank()) return
        accumulatedTranscript = appendConversationDictationSegment(accumulatedTranscript, normalized)
        consecutiveNoSpeechRestarts = 0
        silenceDeadlineElapsedMillis = null
    }

    /** Runs the accumulated transcript through authoritative target validation and the captured delivery policy. */
    private fun finalizeAccumulatedTranscript(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        val transcript = accumulatedTranscript.trim()
        if (transcript.isBlank()) {
            fail(sessionId, target, unresolvedRecognitionFailure ?: ConversationDictationFailure.NoSpeech)
            return
        }
        if (unresolvedRecognitionFailure?.requiresTranscriptReview == true) {
            retainAccumulatedTranscriptForReview(sessionId, target)
            return
        }
        validateAndDeliverTranscript(sessionId, target, transcript)
    }

    /** An explicit finish can still use committed text during a transient reconnect. */
    private val ConversationDictationFailure.requiresTranscriptReview: Boolean
        get() =
            this != ConversationDictationFailure.NoSpeech &&
                this != ConversationDictationFailure.ProviderDisconnected

    /** Releases capture but preserves useful text when a later generation fails fatally. */
    private fun retainAccumulatedTranscriptForReview(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        val transcript = accumulatedTranscript.trim()
        clearRecognitionSession(cancel = true)
        resetTranscriptSession()
        state = ConversationDictationState.ReviewRequired(sessionId, target, transcript)
    }

    /** Preserves useful dictated text when a recognition watchdog or provider operation fails. */
    private fun failOrRetainTranscript(
        sessionId: Long,
        target: ConversationDictationTarget,
        reason: ConversationDictationFailure,
    ) {
        if (accumulatedTranscript.isNotBlank()) {
            retainAccumulatedTranscriptForReview(sessionId, target)
        } else {
            fail(sessionId, target, reason, cancelSession = true)
        }
    }

    /** Rejects callbacks from both superseded logical sessions and destroyed recognizer generations. */
    private fun owns(
        sessionId: Long,
        generationId: Long,
    ): Boolean =
        state.sessionId == sessionId &&
            activeRecognitionGenerationId == generationId &&
            recognitionSession != null

    /** Publishes a terminal failure after releasing every resource held by this session. */
    private fun fail(
        sessionId: Long,
        target: ConversationDictationTarget,
        reason: ConversationDictationFailure,
        cancelSession: Boolean = false,
    ) {
        if (state.sessionId != sessionId) return
        conversationDictationDiagnostic("event=session_failed failure=${reason.name}")
        clearRecognitionSession(cancel = cancelSession)
        resetTranscriptSession()
        state = ConversationDictationState.Failed(sessionId, target, reason)
    }

    /** Releases recognition and microphone ownership, optionally retaining the durable service lease. */
    private fun clearRecognitionSession(
        cancel: Boolean,
        releaseDurableSession: Boolean = true,
    ) {
        sendJob?.cancel()
        sendJob = null
        validatingSessionId = null
        sessionTimeoutHandle?.cancel()
        sessionTimeoutHandle = null
        readinessHandle?.cancel()
        readinessHandle = null
        readinessStartedAtMillis = null
        // A cancelled probe must neither route this session nor record what it was about to learn.
        pendingCallerAudioProbeId = null
        promotionTimeoutHandle?.cancel()
        promotionTimeoutHandle = null
        cancelPendingRestart()
        silenceTimeoutHandle?.cancel()
        silenceTimeoutHandle = null
        silenceDeadlineElapsedMillis = null
        val platformOwnsCaptureClosure =
            runCatching { platform.discardCallerAudio(::finishPlaybackInterruption) }.getOrDefault(false)
        clearRecognitionGeneration(
            cancel = cancel,
            onAudioCaptureFinished = if (platformOwnsCaptureClosure) ({}) else ::finishPlaybackInterruption,
        )
        if (durableSession && releaseDurableSession) {
            durableSession = false
            conversationDictationDiagnostic("event=foreground_service_stop_requested")
            runCatching(stopDurableSession)
        }
        if (releaseDurableSession) {
            durableSessionReady = false
            durableStartAccepted = false
            promotionReadyReceived = false
        }
    }

    /** Restores only playback that this recognition session interrupted, at most once. */
    private fun finishPlaybackInterruption() {
        if (!playbackInterruptedForCapture) return
        playbackInterruptedForCapture = false
        if (microphoneHeld) {
            microphoneHeld = false
            conversationDictationDiagnostic("event=microphone_lease_released")
            runCatching(releaseMicrophone)
        }
        runCatching(onAfterAudioCapture)
    }

    /** Tears down one recognizer generation and optionally acknowledges physical capture closure. */
    private fun clearRecognitionGeneration(
        cancel: Boolean,
        onAudioCaptureFinished: () -> Unit = {},
    ) {
        generationTimeoutHandle?.cancel()
        generationTimeoutHandle = null
        val session = recognitionSession
        recognitionSession = null
        activeRecognitionGenerationId = null
        generationHasSpeech = false
        generationReadyAtElapsedMillis = null
        if (session == null) {
            onAudioCaptureFinished()
            return
        }
        if (cancel) {
            runCatching { session.cancel(onAudioCaptureFinished) }
                .onFailure { onAudioCaptureFinished() }
        }
        runCatching { session.destroy(onAudioCaptureFinished) }
            .onFailure { onAudioCaptureFinished() }
    }

    /** Applies the captured paste-or-send policy after authoritative origin validation. */
    private fun deliverTranscript(
        sessionId: Long,
        target: ConversationDictationTarget,
        transcript: String,
    ) {
        val deliveryMode = requestedDeliveryMode ?: target.deliveryMode
        if (deliveryMode == ConversationDictationDeliveryMode.SendOnFinish) {
            sendTranscriptOnFinish(sessionId, target, transcript)
            return
        }
        repeat(MAX_CONDITIONAL_WRITE_ATTEMPTS) {
            if (state.sessionId != sessionId || !targetAvailable(target.accountRef, target.groupIdHex)) {
                cancel()
                return
            }
            val current = readDraft(target.accountRef, target.groupIdHex)
            when (
                val merge =
                    mergeConversationDictationTranscript(
                        captured = target.capturedDraft,
                        current = current.value,
                        transcript = transcript,
                    )
            ) {
                is ConversationDictationMerge.Applied -> {
                    val accepted =
                        runCatching {
                            writeDraft(
                                target.accountRef,
                                target.groupIdHex,
                                current.revision,
                                merge.value,
                            )
                        }.getOrDefault(false)
                    if (accepted) {
                        complete(target)
                        return
                    }
                }
                ConversationDictationMerge.NeedsReview -> {
                    clearRecognitionSession(cancel = false)
                    state = ConversationDictationState.ReviewRequired(sessionId, target, transcript)
                    return
                }
            }
        }
        clearRecognitionSession(cancel = false)
        state = ConversationDictationState.ReviewRequired(sessionId, target, transcript)
    }

    /** Sends only the immutable origin payload, otherwise retaining the transcript for explicit review. */
    private fun sendTranscriptOnFinish(
        sessionId: Long,
        target: ConversationDictationTarget,
        transcript: String,
    ) {
        val current = readDraft(target.accountRef, target.groupIdHex)
        if (
            current.revision != target.capturedDraftRevision ||
            current.value.text != target.capturedDraft.text
        ) {
            clearRecognitionSession(cancel = false)
            resetTranscriptSession()
            state = ConversationDictationState.ReviewRequired(sessionId, target, transcript)
            return
        }
        val sendRequest = conversationDictationSendRequest(target, transcript)
        val scope = targetValidationScope
        if (sendRequest == null || scope == null) {
            clearRecognitionSession(cancel = false)
            resetTranscriptSession()
            state = ConversationDictationState.ReviewRequired(sessionId, target, transcript)
            return
        }
        clearRecognitionSession(cancel = false, releaseDurableSession = false)
        state = ConversationDictationState.Processing(sessionId, target)
        launchGuardedDispatch(sessionId, target, transcript, sendRequest, scope)
    }

    /** Claims one dispatch for this session and owns its completed, retained, or cancelled outcome. */
    private fun launchGuardedDispatch(
        sessionId: Long,
        target: ConversationDictationTarget,
        transcript: String,
        sendRequest: ConversationDictationSendRequest,
        scope: CoroutineScope,
    ) {
        var emptiedRevision: Long? = null
        val guardedRequest =
            sendRequest.copy(beginDispatch = {
                if (state.sessionId != sessionId || dispatchedSessionId != null) {
                    false
                } else {
                    dispatchedSessionId = sessionId
                    emptiedRevision = emptyDraftForDispatch(target)
                    true
                }
            })
        sendJob =
            scope.launch(start = CoroutineStart.LAZY) {
                if (state.sessionId != sessionId) return@launch
                try {
                    val accepted = dispatchTranscript(guardedRequest)
                    if (state.sessionId != sessionId) return@launch
                    if (accepted == true) {
                        if (emptiedRevision == null) emptyDraftForDispatch(target)
                        complete(target)
                    } else {
                        restoreDraftAfterFailedDispatch(target, emptiedRevision)
                        retainUndeliveredTranscript(sessionId, target, transcript)
                    }
                } finally {
                    if (state.sessionId == sessionId && state is ConversationDictationState.Processing) {
                        restoreDraftAfterFailedDispatch(target, emptiedRevision)
                        retainUndeliveredTranscript(sessionId, target, transcript)
                    }
                }
            }
        sendJob?.start()
    }

    /**
     * Empties the composer in the frame the pending row appears, so the outgoing bubble and the
     * composer never show the same captured text at once. The write is conditional on the captured
     * revision, and its own new revision is what [restoreDraftAfterFailedDispatch] needs to put the
     * text back, so a newer user edit during the send keeps both the edit and its geometry.
     */
    private fun emptyDraftForDispatch(target: ConversationDictationTarget): Long? {
        val emptied =
            runCatching {
                writeDraft(target.accountRef, target.groupIdHex, target.capturedDraftRevision, TextFieldValue(""))
            }.getOrDefault(false)
        if (!emptied) return null
        return runCatching { readDraft(target.accountRef, target.groupIdHex).revision }.getOrNull()
    }

    /** Puts the captured text back only while the composer still holds this dispatch's empty draft. */
    private fun restoreDraftAfterFailedDispatch(
        target: ConversationDictationTarget,
        emptiedRevision: Long?,
    ) {
        val revision = emptiedRevision ?: return
        runCatching {
            writeDraft(target.accountRef, target.groupIdHex, revision, target.capturedDraft)
        }
    }

    /** Bounds result waiting without treating coroutine cancellation as an ordinary send failure. */
    private suspend fun dispatchTranscript(request: ConversationDictationSendRequest): Boolean? =
        try {
            withTimeoutOrNull(PROCESSING_TIMEOUT_MILLIS) {
                sendTranscriptIfOriginUnchanged(request)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }

    private fun retainUndeliveredTranscript(
        sessionId: Long,
        target: ConversationDictationTarget,
        transcript: String,
    ) {
        val dispatched = dispatchedSessionId == sessionId
        clearRecognitionSession(cancel = false)
        resetTranscriptSession()
        state =
            if (dispatched) {
                ConversationDictationState.DeliveryUnknown(sessionId, target, transcript)
            } else {
                ConversationDictationState.ReviewRequired(sessionId, target, transcript)
            }
    }

    /** Keeps durable ownership while asynchronously validating the origin through MDK. */
    private fun validateAndDeliverTranscript(
        sessionId: Long,
        target: ConversationDictationTarget,
        transcript: String,
    ) {
        val validator = targetValidator
        val validationScope = targetValidationScope
        if (validator == null || validationScope == null) {
            deliverTranscript(sessionId, target, transcript)
            return
        }
        if (validatingSessionId == sessionId) return
        // Recognition has produced its terminal result. Release the provider
        // and microphone immediately while the authoritative MDK membership
        // probe runs; session-id ownership still rejects replacement/stale work.
        clearRecognitionSession(cancel = false, releaseDurableSession = false)
        state = ConversationDictationState.Processing(sessionId, target)
        validatingSessionId = sessionId
        validationScope.launch {
            val available =
                try {
                    validateTargetAuthoritatively(validator, target)
                } finally {
                    if (validatingSessionId == sessionId) validatingSessionId = null
                }
            if (state.sessionId != sessionId) return@launch
            if (!available || !targetAvailable(target.accountRef, target.groupIdHex)) {
                cancel()
                return@launch
            }
            deliverTranscript(sessionId, target, transcript)
        }
    }

    /** Bounds the authoritative origin-membership probe and treats provider failures as unavailable. */
    private suspend fun validateTargetAuthoritatively(
        validator: suspend (String, String) -> Boolean,
        target: ConversationDictationTarget,
    ): Boolean =
        try {
            withTimeoutOrNull(PROCESSING_TIMEOUT_MILLIS) {
                validator(target.accountRef, target.groupIdHex)
            } ?: false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }

    /** Releases durable ownership and publishes a completion revision for the origin composer. */
    private fun complete(target: ConversationDictationTarget) {
        clearRecognitionSession(cancel = false)
        resetTranscriptSession()
        val key = ConversationDictationKey.from(target.accountRef, target.groupIdHex)
        completionRevisions[key] = (completionRevisions[key] ?: 0) + 1
        state = ConversationDictationState.Idle
    }

    /** Emits elapsed, PII-free provider-readiness diagnostics. */
    private fun emitReadiness(phase: ConversationDictationReadinessPhase) {
        val startedAt = readinessStartedAtMillis ?: elapsedRealtime()
        onReadinessEvent(
            ConversationDictationReadinessEvent(
                phase = phase,
                elapsedMillis = (elapsedRealtime() - startedAt).coerceAtLeast(0L),
            ),
        )
    }

    /** Arms a watchdog that becomes inert as soon as its recognizer generation is replaced. */
    private fun armGenerationTimeout(
        sessionId: Long,
        generationId: Long,
        delayMillis: Long,
        callback: () -> Unit,
    ) {
        generationTimeoutHandle?.cancel()
        generationTimeoutHandle =
            scheduleTimeout(delayMillis) {
                if (state.sessionId == sessionId && activeRecognitionGenerationId == generationId) callback()
            }
    }

    /** Arms the logical-session safety bound independently of provider generation churn. */
    private fun armSessionTimeout(
        sessionId: Long,
        delayMillis: Long,
        callback: () -> Unit,
    ) {
        sessionTimeoutHandle?.cancel()
        sessionTimeoutHandle =
            scheduleTimeout(delayMillis) {
                if (state.sessionId == sessionId) callback()
            }
    }

    /** Clears all process-memory transcript state after terminal delivery, discard, or failure. */
    private fun resetTranscriptSession() {
        accumulatedTranscript = ""
        finishRequested = false
        dispatchedSessionId = null
        requestedDeliveryMode = null
        generationHasSpeech = false
        consecutiveNoSpeechRestarts = 0
        generationReadyAtElapsedMillis = null
        providerDisconnectRetries = 0
        permissionRetryUsed = false
        unresolvedRecognitionFailure = null
        silenceDeadlineElapsedMillis = null
    }

    private companion object {
        const val PREFERENCES_NAME = CONVERSATION_DICTATION_PREFERENCES_NAME
        const val DISCLOSURE_ACCEPTED_KEY = "composer_dictation_external_provider_disclosed"
        const val STARTING_TIMEOUT_MILLIS = 10_000L
        const val FOREGROUND_READINESS_TIMEOUT_MILLIS = 3_000L
        const val PROVIDER_READINESS_TIMEOUT_MILLIS = 1_500L

        /**
         * Safety bound for a platform probe that never answers. It outlives the platform's own
         * probe timeout so a real answer, including its inconclusive one, always wins.
         */
        const val CALLER_AUDIO_PROBE_TIMEOUT_MILLIS = 6_000L
        const val MAX_SESSION_MILLIS = 65L * 60L * 1_000L
        const val CALLER_AUDIO_DRAIN_TIMEOUT_MILLIS = 90_000L
        const val PROCESSING_TIMEOUT_MILLIS = 20_000L
        const val ORDINARY_SILENCE_MILLIS = 2_000L
        const val MAX_CONSECUTIVE_RAPID_EMPTY_GENERATIONS = 3
        const val GENERATION_RESTART_DELAY_MILLIS = 250L
        const val SUCCESS_RESULT_RESTART_DELAY_MILLIS = 250L
        const val PERMISSION_RETRY_DELAY_MILLIS = 500L
        val PROVIDER_DISCONNECT_RETRY_DELAYS_MILLIS = longArrayOf(500L, 1_000L, 2_000L)
        const val MAX_CONDITIONAL_WRITE_ATTEMPTS = 2
    }
}

internal sealed interface ConversationDictationMerge {
    data class Applied(
        val value: TextFieldValue,
    ) : ConversationDictationMerge

    data object NeedsReview : ConversationDictationMerge
}

/**
 * Merges a recognized segment at the captured selection when concurrent draft edits can be
 * remapped unambiguously; otherwise asks the caller to present an explicit review choice.
 */
@Suppress("ReturnCount")
internal fun mergeConversationDictationTranscript(
    captured: TextFieldValue,
    current: TextFieldValue,
    transcript: String,
): ConversationDictationMerge {
    val recognized = transcript.trim()
    if (recognized.isEmpty()) return ConversationDictationMerge.Applied(current)

    if (current.text == captured.text) {
        return ConversationDictationMerge.Applied(
            insertConversationDictationTranscript(current, captured.selection, recognized),
        )
    }

    val remappedSelection =
        remapConversationDictationSelection(captured, current)
            ?: return ConversationDictationMerge.NeedsReview
    return ConversationDictationMerge.Applied(
        insertConversationDictationTranscript(current, remappedSelection, recognized),
    )
}

/** Appends a recognized segment to the draft and places the cursor after the inserted text. */
internal fun appendConversationDictationTranscript(
    current: TextFieldValue,
    transcript: String,
): TextFieldValue {
    val recognized = transcript.trim()
    if (recognized.isEmpty()) return current
    val separator =
        when {
            current.text.isBlank() -> ""
            current.text.last().isWhitespace() -> ""
            else -> " "
        }
    val appended = current.text + separator + recognized
    return TextFieldValue(appended, TextRange(appended.length))
}

/** Replaces a grapheme-safe selection while preserving readable word boundaries around it. */
private fun insertConversationDictationTranscript(
    current: TextFieldValue,
    selection: TextRange,
    transcript: String,
): TextFieldValue {
    val rawStart = minOf(selection.start, selection.end).coerceIn(0, current.text.length)
    val rawEnd = maxOf(selection.start, selection.end).coerceIn(rawStart, current.text.length)
    val start = current.text.graphemeBoundaryAtOrBefore(rawStart)
    val end = current.text.graphemeBoundaryAtOrAfter(rawEnd)
    val needsLeadingSpace =
        start > 0 &&
            !current.text[start - 1].isWhitespace() &&
            !transcript.first().isWhitespace()
    val needsTrailingSpace =
        end < current.text.length &&
            Character.isLetterOrDigit(current.text.codePointAt(end)) &&
            !transcript.last().isWhitespace()
    val insertion =
        buildString {
            if (needsLeadingSpace) append(' ')
            append(transcript)
            if (needsTrailingSpace) append(' ')
        }
    val inserted = current.text.replaceRange(start, end, insertion)
    return TextFieldValue(inserted, TextRange(start + insertion.length))
}

/**
 * Maps the captured insertion range into a concurrently edited draft using
 * unchanged context on both sides. A unique best anchor is required; edits
 * that remove the selection or make its location ambiguous fail closed so the
 * transcript can be offered for explicit insertion/copying.
 */
@Suppress("ReturnCount")
private fun remapConversationDictationSelection(
    captured: TextFieldValue,
    current: TextFieldValue,
): TextRange? {
    if (captured.text.isEmpty()) return null
    val rawStart = minOf(captured.selection.start, captured.selection.end).coerceIn(0, captured.text.length)
    val rawEnd = maxOf(captured.selection.start, captured.selection.end).coerceIn(rawStart, captured.text.length)
    val start = captured.text.graphemeBoundaryAtOrBefore(rawStart)
    val end = captured.text.graphemeBoundaryAtOrAfter(rawEnd)
    val selected = captured.text.substring(start, end)
    val leftContext = captured.text.substring(0, start)
    val rightContext = captured.text.substring(end)

    val candidates =
        if (selected.isEmpty()) {
            EmptySelectionCandidateWindow.create(
                capturedOffset = start,
                capturedTextLength = captured.text.length,
                currentText = current.text,
            )
        } else {
            current.text
                .occurrenceStarts(selected)
                .map { occurrenceStart -> TextRange(occurrenceStart, occurrenceStart + selected.length) }
        }.filter { range ->
            current.text.isGraphemeBoundary(range.start) && current.text.isGraphemeBoundary(range.end)
        }
    if (candidates.isEmpty()) return null

    val scored =
        candidates.map { candidate ->
            candidate to
                (
                    commonSuffixLengthAt(leftContext, current.text, candidate.start) +
                        commonPrefixLengthAt(rightContext, current.text, candidate.end)
                )
        }
    val bestScore = scored.maxOf { it.second }
    val requiredContext = minOf(MIN_ANCHOR_CONTEXT_CHARS, leftContext.length + rightContext.length).coerceAtLeast(1)
    if (selected.isEmpty() && bestScore < requiredContext) return null
    val best = scored.filter { it.second == bestScore }
    return best.singleOrNull()?.first
}

private object EmptySelectionCandidateWindow {
    /**
     * Produces bounded cursor candidates near both the original and length-shifted offsets for
     * large drafts, while exhaustively scanning small drafts.
     */
    fun create(
        capturedOffset: Int,
        capturedTextLength: Int,
        currentText: String,
    ): List<TextRange> {
        val offsets =
            if (currentText.length <= MAX_EMPTY_SELECTION_SCAN_LENGTH) {
                (0..currentText.length).asSequence()
            } else {
                val originalOffset = capturedOffset.coerceIn(0, currentText.length)
                val shiftedOffset =
                    (capturedOffset + currentText.length - capturedTextLength)
                        .coerceIn(0, currentText.length)
                sequenceOf(originalOffset, shiftedOffset)
                    .distinct()
                    .flatMap { center ->
                        val first = (center - EMPTY_SELECTION_SCAN_RADIUS).coerceAtLeast(0)
                        val last = (center + EMPTY_SELECTION_SCAN_RADIUS).coerceAtMost(currentText.length)
                        (first..last).asSequence()
                    }.distinct()
            }
        return offsets.map(::TextRange).toList()
    }
}

/** Returns every possibly overlapping occurrence used to remap a captured non-empty selection. */
private fun String.occurrenceStarts(needle: String): List<Int> {
    if (needle.isEmpty()) return (0..length).toList()
    val starts = mutableListOf<Int>()
    var from = 0
    while (from <= length - needle.length) {
        val found = indexOf(needle, startIndex = from)
        if (found < 0) break
        starts += found
        from = found + 1
    }
    return starts
}

/** Reports whether [index] is a legal cursor boundary rather than the middle of a grapheme. */
@Suppress("MaxLineLength")
private fun String.isGraphemeBoundary(index: Int): Boolean = index in 0..length && graphemeBoundaryAtOrBefore(index) == index

/** Scores matching right-side anchor context starting at [startInclusive]. */
private fun commonPrefixLengthAt(
    context: String,
    text: String,
    startInclusive: Int,
): Int {
    val limit = minOf(context.length, text.length - startInclusive, MAX_ANCHOR_SCORE_CHARS)
    var index = 0
    while (index < limit && context[index] == text[startInclusive + index]) index += 1
    return index
}

/** Scores matching left-side anchor context ending at [endExclusive]. */
private fun commonSuffixLengthAt(
    context: String,
    text: String,
    endExclusive: Int,
): Int {
    val limit = minOf(context.length, endExclusive, MAX_ANCHOR_SCORE_CHARS)
    var count = 0
    while (
        count < limit &&
        context[context.length - 1 - count] == text[endExclusive - 1 - count]
    ) {
        count += 1
    }
    return count
}

private const val MIN_ANCHOR_CONTEXT_CHARS = 3
private const val MAX_ANCHOR_SCORE_CHARS = 64
private const val MAX_EMPTY_SELECTION_SCAN_LENGTH = 4_096
private const val EMPTY_SELECTION_SCAN_RADIUS = 1_024
private const val READINESS_UI_FRAME_MILLIS = 16L

@Suppress("MaxLineLength")
internal class AndroidConversationDictationPlatform(
    private val context: Context,
) : ConversationDictationPlatform {
    private var sessionRecognitionService: ComponentName? = null
    private var callerAudioCapture: ConversationDictationCallerAudio? = null
    private var nextCallerAudioSessionId = 0L

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

    /** Starts a session by resolving the provider afresh, so install and selection changes land. */
    override fun recognitionConfigured(): Boolean = resolveAndPinRecognitionService() != null

    /**
     * Rechecks the pinned service without switching providers during a session. A check that
     * arrives before this session pinned anything resolves instead of reporting an installed
     * provider as missing, which would fail the session on call order alone.
     */
    override fun recognitionAvailable(): Boolean {
        val pinned = sessionRecognitionService ?: resolveAndPinRecognitionService()
        val available =
            conversationDictationRecognitionServiceAvailable(pinned, eligibleRecognitionServices())
        conversationDictationDiagnostic("event=recognition_service_available available=$available")
        return available
    }

    /** Names the pinned provider's package without switching providers to answer the question. */
    override fun speechProviderPackage(): String? {
        val pinned = sessionRecognitionService ?: resolvedRecognitionService(selectedRecognitionService())
        return pinned?.packageName
    }

    /** Resolves one component and pins it as this session's provider. */
    private fun resolveAndPinRecognitionService(): ComponentName? {
        val selected = selectedRecognitionService()
        conversationDictationDiagnostic("event=selected_service ${selected.describeProvider()}")
        return resolvedRecognitionService(selected).also { sessionRecognitionService = it }
    }

    /** Reports whether Android can route the provider-owned compatibility recognition UI. */
    override fun recognitionActivityAvailable(): Boolean {
        val resolved = conversationDictationRecognitionActivityIntent().resolveActivity(context.packageManager)
        conversationDictationDiagnostic(
            "event=provider_activity_resolve component=${resolved?.flattenToShortString() ?: "none"}",
        )
        return resolved != null
    }

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
    override fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession {
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
    private fun openCallerAudioStreamIfProviderCannotRecord(listener: ConversationDictationRecognitionListener): ConversationDictationCallerAudioStream? {
        val systemSelected = selectedRecognitionService() != null
        val providerRecords = providerCanRecord(sessionRecognitionService)
        val supported = conversationDictationAudioSourceSupported()
        val capture =
            if (providerRecords || !supported) {
                null
            } else {
                callerAudioCapture ?: createCallerAudioCapture()
            }
        val source =
            capture?.openProviderStream { failure ->
                val reason =
                    when (failure) {
                        ConversationDictationCallerAudioFailure.BufferFull -> ConversationDictationFailure.AudioBufferFull
                    }
                Handler(Looper.getMainLooper()).post { listener.onError(reason) }
            }
        conversationDictationDiagnostic(
            "event=caller_audio_mode enabled=${source != null} " +
                "system_selected=$systemSelected provider_records=$providerRecords supported=$supported",
        )
        return source
    }

    /** Reports retained audio even after microphone closure. */
    override fun callerAudioHasPending(): Boolean = callerAudioCapture?.hasPending() == true

    /** Uses capture activity rather than recognizer callbacks delayed by chunk buffering. */
    override fun callerAudioSilenceMillis(): Long? = callerAudioCapture?.silenceMillis()

    /** Returns recorder closure on the main thread before creating another recognizer. */
    override fun finishCallerAudioCapture(onClosed: () -> Unit): Boolean {
        val capture = callerAudioCapture ?: return false
        capture.finish {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                onClosed()
            } else {
                Handler(Looper.getMainLooper()).post(onClosed)
            }
        }
        return true
    }

    private fun createCallerAudioCapture(): ConversationDictationCallerAudio? {
        val capture = ConversationDictationCallerAudio.open(++nextCallerAudioSessionId)
        callerAudioCapture = capture
        return capture
    }

    override fun discardCallerAudio(onClosed: () -> Unit): Boolean {
        val capture = callerAudioCapture
        callerAudioCapture = null
        if (capture == null) return false
        capture.discard(onClosed)
        return true
    }

    /** Reports what is already known about the resolved provider build, without asking it. */
    override fun callerAudioRequirement(): ConversationDictationCallerAudioRequirement {
        val requirement = knownCallerAudioRequirement(callerAudioProvider())
        conversationDictationDiagnostic("event=caller_audio_known requirement=${requirement.name}")
        return requirement
    }

    /**
     * Asks the resolved provider build whether it reads a caller-supplied descriptor, records a
     * conclusive answer for that exact build, and reports the answer once on the main thread.
     */
    override fun probeCallerAudioSupport(callback: (ConversationDictationCallerAudioRequirement) -> Unit): ConversationDictationTimeoutHandle {
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
    private fun knownCallerAudioRequirement(provider: ConversationDictationCallerAudioProvider?): ConversationDictationCallerAudioRequirement =
        when {
            providerCanRecord(provider?.component) -> ConversationDictationCallerAudioRequirement.NotNeeded
            !conversationDictationAudioSourceSupported() -> ConversationDictationCallerAudioRequirement.Unsupported
            provider == null -> ConversationDictationCallerAudioRequirement.Unknown
            else -> callerAudioVerdicts.recorded(provider.packageName, provider.versionCode)
        }

    /**
     * Names the provider build a verdict can belong to.
     *
     * Both the component and its version code are required: a verdict describes one installed
     * build, so a version lookup that fails must leave the answer unknown rather than key a cache
     * entry on a version this app never read.
     */
    private fun callerAudioProvider(): ConversationDictationCallerAudioProvider? {
        val component = resolvedRecognitionService(selectedRecognitionService()) ?: return null
        return providerVersionCode(component.packageName)?.let { versionCode ->
            ConversationDictationCallerAudioProvider(component, versionCode)
        }
    }

    /** Preinstalled recognizers also receive Android's microphone binding capability. */
    private fun providerCanRecord(component: ComponentName?): Boolean {
        if (selectedRecognitionService() != null) return true
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

    private fun callerAudioPreferences() = context.getSharedPreferences(CONVERSATION_DICTATION_PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Parses Android's secure setting for the currently selected recognition service. */
    private fun selectedRecognitionService(): ComponentName? =
        conversationDictationRecognitionServiceComponent(
            Settings.Secure.getString(
                context.contentResolver,
                VOICE_RECOGNITION_SERVICE_SETTING,
            ),
        )

    /** Keeps the UI inside White Noise when Android leaves the selected-service setting empty. */
    private fun resolvedRecognitionService(selected: ComponentName?): ComponentName? {
        val discovered = eligibleRecognitionServices()
        val activity = conversationDictationRecognitionActivityIntent().resolveActivity(context.packageManager)
        val resolved = conversationDictationRecognitionService(selected, activity, discovered)
        val source =
            when {
                resolved == null -> "none"
                resolved == selected -> "setting"
                activity != null && resolved.packageName == activity.packageName -> "activity_package"
                else -> "unique_discovered"
            }
        conversationDictationDiagnostic(
            "event=recognition_service_resolved source=$source ${resolved.describeProvider()} " +
                "discovered=${discovered.size}",
        )
        return resolved
    }

    private fun eligibleRecognitionServices(): List<ComponentName> =
        context.packageManager
            .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo }
            .filter { it.enabled && it.exported && it.applicationInfo.enabled }
            .map { ComponentName(it.packageName, it.name) }

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
        verdicts.record(provider.packageName, provider.versionCode, answered)
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
                    val mapped = error.toConversationDictationFailure(recognitionService.packageName)
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
            delivery()
            return
        }
        callerAudio?.onFeedClosed {
            if (Looper.myLooper() == Looper.getMainLooper()) delivery() else mainHandler.post(delivery)
        } ?: delivery()
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

    override fun acknowledgeCallerAudio(): Boolean = callerAudio?.acknowledge() == true

    override fun retryCallerAudio(): Boolean = callerAudio?.retry() == true

    /** Releases the recognizer when no capture acknowledgement is required. */
    override fun destroy() = destroy {}
}

/** Reports only whether a callback contained text, never the recognized content. */
private fun Bundle?.hasRecognitionText(): Boolean =
    this
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.any { it.isNotBlank() } == true

/**
 * Maps unstable Android speech errors without presenting a local model failure as a network error.
 * Offline Voice Input reports ERROR_SERVER when its engine/model is unavailable; opening that
 * provider is the only useful recovery. Other providers retain Android's normal network mapping.
 */
internal fun Int.toConversationDictationFailure(providerPackage: String? = null): ConversationDictationFailure {
    if (this == SpeechRecognizer.ERROR_SERVER && providerPackage.isOfflineVoiceInputPackage()) {
        return ConversationDictationFailure.ProviderUnavailable
    }
    return when (this) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ConversationDictationFailure.PermissionDenied
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> ConversationDictationFailure.NoSpeech
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        -> ConversationDictationFailure.Network
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> ConversationDictationFailure.ProviderDisconnected
        SpeechRecognizer.ERROR_AUDIO -> ConversationDictationFailure.MicrophoneInUse
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
        -> ConversationDictationFailure.RecognizerBusy
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        -> ConversationDictationFailure.ProviderUnavailable
        else -> ConversationDictationFailure.Unknown
    }
}

@Suppress("MaxLineLength")
private fun String?.isOfflineVoiceInputPackage(): Boolean = this == OFFLINE_VOICE_INPUT_PACKAGE || this == OFFLINE_VOICE_INPUT_CALLER_FIX_PACKAGE

private const val OFFLINE_VOICE_INPUT_PACKAGE = "dev.notune.transcribe"
private const val OFFLINE_VOICE_INPUT_CALLER_FIX_PACKAGE = "dev.notune.transcribe.callerfix"

/** Posts a cancellable main-thread watchdog used to bound recognizer state transitions. */
private fun scheduleConversationDictationTimeout(
    delayMillis: Long,
    callback: () -> Unit,
): ConversationDictationTimeoutHandle {
    val handler = Handler(Looper.getMainLooper())
    val runnable = Runnable(callback)
    handler.postDelayed(runnable, delayMillis)
    return ConversationDictationTimeoutHandle { handler.removeCallbacks(runnable) }
}
