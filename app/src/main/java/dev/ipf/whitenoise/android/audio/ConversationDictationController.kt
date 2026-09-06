@file:Suppress("TooManyFunctions")

package dev.ipf.whitenoise.android.audio

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.core.content.PermissionChecker
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
import kotlin.concurrent.thread

private const val DICTATION_DIAGNOSTIC_TAG = "WNDictation"

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
    PermissionDenied,
    PermissionPermanentlyDenied,
    MicrophoneMuted,
    MicrophoneInUse,
    NoSpeech,
    Network,
    RecognizerBusy,
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

    /** Abandons recognition without requesting a final result. */
    fun cancel()

    /** Releases the provider resources owned by this generation. */
    fun destroy()
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

    /** Whether an in-process recognition service can be created. */
    fun recognitionAvailable(): Boolean

    /** Whether Android can resolve the provider-owned recognition Activity. */
    fun recognitionActivityAvailable(): Boolean = true

    /** Checks provider-Activity readiness and returns a handle that invalidates late callbacks. */
    fun checkRecognitionActivity(callback: (Boolean) -> Unit): ConversationDictationTimeoutHandle {
        callback(recognitionActivityAvailable())
        return ConversationDictationTimeoutHandle {}
    }

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
    private val tryAcquireMicrophone: () -> Boolean = { true },
    private val releaseMicrophone: () -> Unit = {},
    private val startDurableSession: () -> Boolean = { true },
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
        tryAcquireMicrophone = tryAcquireMicrophone,
        releaseMicrophone = releaseMicrophone,
        startDurableSession = { ConversationDictationForegroundService.start(context.applicationContext) },
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
    private var microphoneHeld = false
    private var durableSession = false
    private var validatingSessionId: Long? = null
    private var accumulatedTranscript = ""
    private var finishRequested by mutableStateOf(false)
    private var dispatchedSessionId by mutableStateOf<Long?>(null)
    private var sendJob: Job? = null
    private var requestedDeliveryMode: ConversationDictationDeliveryMode? = null
    private var generationHasSpeech = false
    private var consecutiveNoSpeechRestarts = 0

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

    val completionActionsEnabled: Boolean
        get() = !finishRequested && !deliveryInProgress && activeRecognitionGenerationId != null

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
        requestedDeliveryMode = deliveryMode
        if (current is ConversationDictationState.Processing) {
            finishRequested = true
            silenceTimeoutHandle?.cancel()
            silenceTimeoutHandle = null
            return
        }
        if (current !is ConversationDictationState.Starting && current !is ConversationDictationState.Listening) return
        val sessionId = current.sessionId ?: return
        val target = current.target ?: return
        val generationId = activeRecognitionGenerationId ?: return
        finishRequested = true
        silenceTimeoutHandle?.cancel()
        silenceTimeoutHandle = null
        if (accumulatedTranscript.isNotBlank() && !generationHasSpeech) {
            clearRecognitionGeneration(cancel = true)
            finalizeAccumulatedTranscript(sessionId, target)
            return
        }
        state = ConversationDictationState.Processing(sessionId, target)
        armGenerationTimeout(sessionId, generationId, PROCESSING_TIMEOUT_MILLIS) {
            failOrRetainTranscript(sessionId, target, ConversationDictationFailure.TimedOut)
        }
        runCatching { recognitionSession?.stop() }
            .onFailure { failOrRetainTranscript(sessionId, target, ConversationDictationFailure.Unknown) }
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
    fun onDurableServiceDestroyed() {
        if (!durableSession) return
        durableSession = false
        cancel()
    }

    /** Routes a captured target to either app-owned recognition or provider compatibility UI. */
    private fun startTarget(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        conversationDictationDiagnostic("event=start_target mode=${target.mode.name}")
        when (target.mode) {
            ConversationDictationMode.InApp -> startOrRequestPermission(sessionId, target)
            ConversationDictationMode.ProviderActivity -> prepareProviderActivity(sessionId, target)
        }
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

    /** Checks provider, runtime permission, and app-op before creating a recognizer generation. */
    private fun startOrRequestPermission(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        state = ConversationDictationState.Starting(sessionId, target)
        val configured = platform.recognitionConfigured()
        conversationDictationDiagnostic("event=recognition_configured configured=$configured")
        val microphoneAccess = platform.microphoneAccess()
        conversationDictationDiagnostic("event=microphone_preflight access=${microphoneAccess.name}")
        // Provider-owned capture needs no app grant, but must not bypass a known privacy denial.
        if (
            !configured &&
            (
                microphoneAccess == ConversationDictationMicrophoneAccess.Granted ||
                    microphoneAccess == ConversationDictationMicrophoneAccess.RuntimePermissionRequired
            )
        ) {
            conversationDictationDiagnostic("event=provider_activity_fallback reason=service_not_configured")
            prepareProviderActivity(sessionId, target)
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

    /** Publishes foreground-service ownership before Android can dispatch its queued start. */
    private fun ensureDurableSession(
        sessionId: Long,
        target: ConversationDictationTarget,
    ): Boolean {
        if (durableSession) return true
        durableSession = true
        val started = runCatching(startDurableSession).getOrDefault(false)
        conversationDictationDiagnostic("event=foreground_service_start requested=$started")
        if (started) return true
        durableSession = false
        fail(sessionId, target, ConversationDictationFailure.Unknown)
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
        if (!microphoneHeld) {
            val acquired = tryAcquireMicrophone()
            conversationDictationDiagnostic("event=microphone_lease acquired=$acquired")
            if (!acquired) {
                fail(sessionId, target, ConversationDictationFailure.MicrophoneInUse)
                return
            }
            microphoneHeld = true
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
                override fun onReady() {
                    conversationDictationDiagnostic("event=callback_ready generation=$generationId")
                    if (!owns(sessionId, generationId) || state !is ConversationDictationState.Starting) return
                    generationTimeoutHandle?.cancel()
                    generationTimeoutHandle = null
                    state =
                        ConversationDictationState.Listening(
                            sessionId = sessionId,
                            target = target,
                            startedAtElapsedMillis = elapsedRealtime(),
                        )
                }

                override fun onBeginningOfSpeech() {
                    conversationDictationDiagnostic("event=callback_beginning_of_speech generation=$generationId")
                    if (!owns(sessionId, generationId)) return
                    generationHasSpeech = true
                    silenceTimeoutHandle?.cancel()
                    silenceTimeoutHandle = null
                }

                override fun onEndOfSpeech() {
                    conversationDictationDiagnostic("event=callback_end_of_speech generation=$generationId")
                    val recognitionActive =
                        state is ConversationDictationState.Starting || state is ConversationDictationState.Listening
                    if (!owns(sessionId, generationId) || !recognitionActive) {
                        return
                    }
                    state = ConversationDictationState.Processing(sessionId, target)
                    armGenerationTimeout(sessionId, generationId, PROCESSING_TIMEOUT_MILLIS) {
                        failOrRetainTranscript(sessionId, target, ConversationDictationFailure.TimedOut)
                    }
                }

                override fun onResult(transcript: String?) {
                    conversationDictationDiagnostic(
                        "event=callback_result generation=$generationId has_text=${!transcript.isNullOrBlank()}",
                    )
                    if (!owns(sessionId, generationId)) return
                    val recognized = transcript?.trim().orEmpty()
                    if (recognized.isBlank()) {
                        clearRecognitionGeneration(cancel = false)
                        if (finishRequested) {
                            finalizeAccumulatedTranscript(sessionId, target)
                        } else {
                            restartAfterNoSpeech(sessionId, target)
                        }
                        return
                    }
                    if (!targetAvailable(target.accountRef, target.groupIdHex)) {
                        cancel()
                        return
                    }
                    clearRecognitionGeneration(cancel = false)
                    commitSegment(recognized)
                    if (finishRequested) {
                        finalizeAccumulatedTranscript(sessionId, target)
                    } else {
                        restartRecognition(sessionId, target)
                    }
                }

                override fun onError(error: ConversationDictationFailure) {
                    conversationDictationDiagnostic(
                        "event=callback_error generation=$generationId failure=${error.name}",
                    )
                    if (!owns(sessionId, generationId)) return
                    clearRecognitionGeneration(cancel = false)
                    when {
                        finishRequested && accumulatedTranscript.isNotBlank() ->
                            finalizeAccumulatedTranscript(sessionId, target)
                        !finishRequested && error == ConversationDictationFailure.NoSpeech ->
                            restartAfterNoSpeech(sessionId, target)
                        !finishRequested &&
                            error == ConversationDictationFailure.PermissionDenied &&
                            accumulatedTranscript.isBlank() -> {
                            clearRecognitionSession(cancel = false)
                            resetTranscriptSession()
                            when (platform.microphoneAccess()) {
                                ConversationDictationMicrophoneAccess.Granted ->
                                    prepareProviderActivity(sessionId, target)
                                ConversationDictationMicrophoneAccess.RuntimePermissionRequired ->
                                    fail(sessionId, target, ConversationDictationFailure.PermissionDenied)
                                ConversationDictationMicrophoneAccess.AppOpDenied ->
                                    fail(sessionId, target, ConversationDictationFailure.PermissionPermanentlyDenied)
                                ConversationDictationMicrophoneAccess.MicrophoneMuted ->
                                    fail(sessionId, target, ConversationDictationFailure.MicrophoneMuted)
                            }
                        }
                        accumulatedTranscript.isNotBlank() -> retainAccumulatedTranscriptForReview(sessionId, target)
                        else -> fail(sessionId, target, error)
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

    /** Bounds consecutive empty provider generations so a broken recognizer cannot spin forever. */
    private fun restartAfterNoSpeech(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        if (consecutiveNoSpeechRestarts >= MAX_CONSECUTIVE_NO_SPEECH_RESTARTS) {
            failOrRetainTranscript(sessionId, target, ConversationDictationFailure.NoSpeech)
            return
        }
        consecutiveNoSpeechRestarts += 1
        restartRecognition(sessionId, target)
    }

    /** Opens a fresh provider generation while retaining the logical session's microphone lease and transcript. */
    private fun restartRecognition(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        if (state.sessionId != sessionId || finishRequested) return
        startRecognition(sessionId, target)
        val silenceMillis = target.finishAfterSilenceMillis ?: return
        if (accumulatedTranscript.isBlank()) return
        silenceTimeoutHandle?.cancel()
        silenceTimeoutHandle =
            scheduleTimeout(silenceMillis) {
                if (state.sessionId == sessionId && !generationHasSpeech && accumulatedTranscript.isNotBlank()) {
                    finishRequested = true
                    clearRecognitionGeneration(cancel = true)
                    finalizeAccumulatedTranscript(sessionId, target)
                }
            }
    }

    /** Appends one provider-final segment; generation ownership rejects duplicate callbacks. */
    private fun commitSegment(segment: String) {
        val normalized = segment.trim()
        if (normalized.isBlank()) return
        accumulatedTranscript = appendConversationDictationSegment(accumulatedTranscript, normalized)
        consecutiveNoSpeechRestarts = 0
    }

    /** Runs the accumulated transcript through authoritative target validation and the captured delivery policy. */
    private fun finalizeAccumulatedTranscript(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        val transcript = accumulatedTranscript.trim()
        if (transcript.isBlank()) {
            fail(sessionId, target, ConversationDictationFailure.NoSpeech)
            return
        }
        validateAndDeliverTranscript(sessionId, target, transcript)
    }

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
        silenceTimeoutHandle?.cancel()
        silenceTimeoutHandle = null
        clearRecognitionGeneration(cancel)
        if (microphoneHeld) {
            microphoneHeld = false
            conversationDictationDiagnostic("event=microphone_lease_released")
            runCatching(releaseMicrophone)
        }
        if (durableSession && releaseDurableSession) {
            durableSession = false
            conversationDictationDiagnostic("event=foreground_service_stop_requested")
            runCatching(stopDurableSession)
        }
    }

    /** Tears down one recognizer generation without releasing logical-session resources. */
    private fun clearRecognitionGeneration(cancel: Boolean) {
        generationTimeoutHandle?.cancel()
        generationTimeoutHandle = null
        val session = recognitionSession
        recognitionSession = null
        activeRecognitionGenerationId = null
        generationHasSpeech = false
        if (cancel) runCatching { session?.cancel() }
        runCatching { session?.destroy() }
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
        val guardedRequest =
            sendRequest.copy(beginDispatch = {
                if (state.sessionId != sessionId || dispatchedSessionId != null) {
                    false
                } else {
                    dispatchedSessionId = sessionId
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
                        runCatching {
                            writeDraft(
                                target.accountRef,
                                target.groupIdHex,
                                target.capturedDraftRevision,
                                TextFieldValue(""),
                            )
                        }
                        complete(target)
                    } else {
                        retainUndeliveredTranscript(sessionId, target, transcript)
                    }
                } finally {
                    if (state.sessionId == sessionId && state is ConversationDictationState.Processing) {
                        retainUndeliveredTranscript(sessionId, target, transcript)
                    }
                }
            }
        sendJob?.start()
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
    }

    private companion object {
        const val PREFERENCES_NAME = "whitenoise"
        const val DISCLOSURE_ACCEPTED_KEY = "composer_dictation_external_provider_disclosed"
        const val STARTING_TIMEOUT_MILLIS = 10_000L
        const val PROVIDER_READINESS_TIMEOUT_MILLIS = 1_500L
        const val MAX_SESSION_MILLIS = 30L * 60L * 1_000L
        const val PROCESSING_TIMEOUT_MILLIS = 20_000L
        const val MAX_CONSECUTIVE_NO_SPEECH_RESTARTS = 8
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

    /** Includes the RECORD_AUDIO app-op so privacy-policy denial cannot masquerade as a usable grant. */
    override fun microphoneAccess(): ConversationDictationMicrophoneAccess {
        if (!hasRecordAudioPermission()) return ConversationDictationMicrophoneAccess.RuntimePermissionRequired
        // Android folds device-wide microphone privacy into this effective permission check,
        // but does not expose the current software-toggle state to ordinary apps. A real app
        // permission revocation was already handled above, so route any remaining effective
        // denial to visible privacy recovery without starting a silent recording or changing
        // the user's privacy toggle.
        val access =
            if (
                PermissionChecker.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PermissionChecker.PERMISSION_GRANTED
            ) {
                ConversationDictationMicrophoneAccess.Granted
            } else {
                ConversationDictationMicrophoneAccess.MicrophoneMuted
            }
        conversationDictationDiagnostic("event=effective_record_audio_access access=${access.name}")
        return access
    }

    /** Resolves one explicit or unambiguous installed service before requesting app microphone access. */
    override fun recognitionConfigured(): Boolean {
        val selected = selectedRecognitionService()
        conversationDictationDiagnostic("event=selected_service ${selected.describeProvider()}")
        sessionRecognitionService = resolvedRecognitionService(selected)
        return sessionRecognitionService != null
    }

    /** Checks the pinned service again without switching providers during a session. */
    override fun recognitionAvailable(): Boolean {
        val available =
            conversationDictationRecognitionServiceAvailable(sessionRecognitionService, eligibleRecognitionServices())
        conversationDictationDiagnostic("event=recognition_service_available available=$available")
        return available
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
            listener = listener,
        )
    }

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
        val versionCode =
            runCatching {
                PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(packageName, 0))
            }.getOrNull()
        return "component=${flattenToShortString()} version_code=${versionCode ?: "unknown"}"
    }
}

private class AndroidConversationDictationRecognitionSession(
    private val context: Context,
    recognitionService: ComponentName,
    listener: ConversationDictationRecognitionListener,
) : ConversationDictationRecognitionSession {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context, recognitionService)
    private var destroyed = false

    init {
        recognizer.setRecognitionListener(
            @Suppress("TooManyFunctions")
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = listener.onReady()

                override fun onBeginningOfSpeech() = listener.onBeginningOfSpeech()

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = listener.onEndOfSpeech()

                override fun onError(error: Int) {
                    val mapped = error.toConversationDictationFailure()
                    conversationDictationDiagnostic("event=platform_error code=$error failure=${mapped.name}")
                    listener.onError(mapped)
                }

                override fun onResults(results: Bundle?) {
                    listener.onResult(
                        results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull(),
                    )
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
        conversationDictationDiagnostic("event=platform_start_listening")
        recognizer.startListening(conversationDictationRecognitionIntent())
    }

    /** Requests the provider to finish the current utterance and return its final result. */
    override fun stop() {
        conversationDictationDiagnostic("event=platform_stop_listening")
        recognizer.stopListening()
    }

    /** Cancels provider work when the controller no longer needs a result. */
    override fun cancel() {
        conversationDictationDiagnostic("event=platform_cancel")
        recognizer.cancel()
    }

    /** Releases the platform recognizer exactly once and invalidates further use of this session. */
    override fun destroy() {
        if (destroyed) return
        destroyed = true
        conversationDictationDiagnostic("event=platform_destroy")
        recognizer.destroy()
    }
}

/** Reports only whether a callback contained text, never the recognized content. */
private fun Bundle?.hasRecognitionText(): Boolean =
    this
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.any { it.isNotBlank() } == true

/** Maps unstable Android speech error codes into the controller's user-facing failure model. */
private fun Int.toConversationDictationFailure(): ConversationDictationFailure =
    when (this) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ConversationDictationFailure.PermissionDenied
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> ConversationDictationFailure.NoSpeech
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        -> ConversationDictationFailure.Network
        SpeechRecognizer.ERROR_AUDIO -> ConversationDictationFailure.MicrophoneInUse
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
        -> ConversationDictationFailure.RecognizerBusy
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        -> ConversationDictationFailure.ProviderUnavailable
        else -> ConversationDictationFailure.Unknown
    }

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
