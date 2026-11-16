@file:Suppress("TooManyFunctions")

package dev.ipf.whitenoise.android.audio

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.core.graphemeBoundaryAtOrAfter
import dev.ipf.whitenoise.android.core.graphemeBoundaryAtOrBefore
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

/** Emits technical speech-service state to logcat and the consented local export sink. */
internal fun conversationDictationDiagnostic(event: String) {
    DictationDiagnostics.record(event)
}

/** Empty recognition outcomes share recovery policy without treating a no-match as proof of silence. */
internal val ConversationDictationFailure.isEmptyRecognition: Boolean
    get() = this == ConversationDictationFailure.NoSpeech || this == ConversationDictationFailure.NoMatch

/** Distinguishes completed text from recognition interrupted before its final result. */
internal enum class DictationFailureCapture {
    Complete,
    Cancel,
    CancelIncomplete,
}

internal data class ConversationDictationKey(
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
 * Caller-audio mode keeps bounded PCM in volatile memory without persisting it.
 * MDK owns draft writes and message dispatch. Recognized text is recovered into the original
 * draft on failure; the captured Send payload remains immutable and uncertain sends never retry.
 */
@Stable
@Suppress("LargeClass", "ReturnCount", "TooManyFunctions")
internal class ConversationDictationController internal constructor(
    internal val platform: ConversationDictationPlatform,
    internal val microphoneRecoveryAllowed: () -> Boolean = { true },
    internal val readDraft: (accountRef: String, groupIdHex: String) -> ConversationDictationDraftSnapshot,
    internal val writeDraft: (
        accountRef: String,
        groupIdHex: String,
        expectedRevision: Long,
        value: TextFieldValue,
    ) -> Long?,
    internal val targetAvailable: (accountRef: String, groupIdHex: String) -> Boolean = { _, _ -> true },
    private val targetReplyAvailable: (
        accountRef: String,
        groupIdHex: String,
        replyToMessageIdHex: String?,
    ) -> Boolean? = { _, _, _ -> true },
    internal val targetValidator: (
        suspend (accountRef: String, groupIdHex: String) -> ConversationDictationTargetValidation
    )? = null,
    internal val targetValidationScope: CoroutineScope? = null,
    internal val onBeforeRecognition: (ConversationDictationTarget) -> Unit = {},
    private val onAfterAudioCapture: () -> Unit = {},
    private val tryAcquireMicrophone: () -> Boolean = { true },
    private val releaseMicrophone: () -> Unit = {},
    internal val startDurableSession: (String, () -> Unit) -> Boolean = { _, ready ->
        ready()
        true
    },
    private val stopDurableSession: () -> Unit = {},
    internal val silenceDeliveryMode: () -> ConversationDictationDeliveryMode = {
        ConversationDictationDeliveryMode.PasteIntoDraft
    },
    internal val sendTranscriptIfOriginUnchanged: suspend (ConversationDictationSendRequest) -> Boolean = { false },
    private val disclosureAccepted: () -> Boolean,
    private val markDisclosureAccepted: () -> Unit,
    private val offlineDisclosureAccepted: () -> Boolean = { false },
    private val markOfflineDisclosureAccepted: () -> Unit = {},
    internal val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    internal val scheduleTimeout: (delayMillis: Long, callback: () -> Unit) -> ConversationDictationTimeoutHandle =
        ::scheduleConversationDictationTimeout,
    internal val finishAfterSilenceMillis: () -> Long? = { null },
    private val pauseOtherAudio: () -> Boolean = { true },
    internal val onReadinessEvent: (ConversationDictationReadinessEvent) -> Unit = {},
    internal val onRecoveryExpired: () -> Unit = {},
    internal val onSendBlocked: () -> Unit = {},
    completionWrite: ((String, String, Long, TextFieldValue) -> ConversationDictationDraftWrite)? = null,
) {
    constructor(
        context: Context,
        microphoneRecoveryAllowed: () -> Boolean = { true },
        readDraft: (accountRef: String, groupIdHex: String) -> ConversationDictationDraftSnapshot,
        writeDraft: (
            accountRef: String,
            groupIdHex: String,
            expectedRevision: Long,
            value: TextFieldValue,
        ) -> Long?,
        targetAvailable: (accountRef: String, groupIdHex: String) -> Boolean,
        targetReplyAvailable: (accountRef: String, groupIdHex: String, replyToMessageIdHex: String?) -> Boolean? =
            { _, _, _ -> true },
        targetValidator: suspend (accountRef: String, groupIdHex: String) -> ConversationDictationTargetValidation,
        targetValidationScope: CoroutineScope,
        onBeforeRecognition: (ConversationDictationTarget) -> Unit,
        onAfterAudioCapture: () -> Unit,
        tryAcquireMicrophone: () -> Boolean,
        releaseMicrophone: () -> Unit,
        finishAfterSilenceMillis: () -> Long? = { null },
        pauseOtherAudio: () -> Boolean = { true },
        silenceDeliveryMode: () -> ConversationDictationDeliveryMode = {
            ConversationDictationDeliveryMode.PasteIntoDraft
        },
        sendTranscriptIfOriginUnchanged: suspend (ConversationDictationSendRequest) -> Boolean = { false },
        onSendBlocked: () -> Unit = {},
        completionWrite: ((String, String, Long, TextFieldValue) -> ConversationDictationDraftWrite)? = null,
    ) : this(
        platform = AndroidConversationDictationPlatform(context.applicationContext),
        microphoneRecoveryAllowed = microphoneRecoveryAllowed,
        readDraft = readDraft,
        writeDraft = writeDraft,
        completionWrite = completionWrite,
        targetAvailable = targetAvailable,
        targetReplyAvailable = targetReplyAvailable,
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
        onRecoveryExpired = { notifyConversationDictationRecoveryExpired(context.applicationContext) },
        finishAfterSilenceMillis = finishAfterSilenceMillis,
        pauseOtherAudio = pauseOtherAudio,
        silenceDeliveryMode = silenceDeliveryMode,
        sendTranscriptIfOriginUnchanged = sendTranscriptIfOriginUnchanged,
        onSendBlocked = onSendBlocked,
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
        offlineDisclosureAccepted = {
            context
                .applicationContext
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(OFFLINE_DISCLOSURE_ACCEPTED_KEY, false)
        },
        markOfflineDisclosureAccepted = {
            context
                .applicationContext
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(OFFLINE_DISCLOSURE_ACCEPTED_KEY, true)
                .apply()
        },
    )

    private var observedState: ConversationDictationState by mutableStateOf(ConversationDictationState.Idle)
    var state: ConversationDictationState
        get() = observedState
        internal set(value) {
            val previous = observedState
            DictationDiagnostics.activeSession = value.sessionId ?: 0L
            if (previous.diagnosticPhase() != value.diagnosticPhase()) {
                DictationDiagnostics.record(
                    "event=state_changed session=${value.sessionId ?: previous.sessionId ?: 0L} " +
                        "from=${previous.diagnosticPhase()} to=${value.diagnosticPhase()}",
                )
            }
            observedState = value
        }

    internal fun conversationDictationDiagnostic(event: String) {
        DictationDiagnostics.record("$event session=${state.sessionId ?: 0L}")
    }

    internal val completionRevisions = mutableStateMapOf<ConversationDictationKey, Int>()
    internal val pendingSendRevisions = mutableStateMapOf<ConversationDictationKey, Int>()
    private var nextSessionId = 0L
    private val notificationInstanceId = UUID.randomUUID().toString()
    internal val recognitionDeadlines = ConversationDictationRecognitionDeadlines()
    internal var captureClosureTimeoutHandle: ConversationDictationTimeoutHandle? = null
    internal var captureClosureGeneration = 0L
    internal var silenceTimeoutHandle: ConversationDictationTimeoutHandle? = null
    internal var readinessHandle: ConversationDictationTimeoutHandle? = null
    internal var readinessStartedAtMillis: Long? = null
    internal var nextCallerAudioProbeId = 0L
    internal var pendingCallerAudioProbeId: Long? = null
    internal val microphone =
        ConversationDictationMicrophoneLease(
            tryAcquire = tryAcquireMicrophone,
            releaseMicrophone = releaseMicrophone,
            afterCapture = onAfterAudioCapture,
            diagnostic = ::conversationDictationDiagnostic,
        )
    internal val foregroundLease =
        ConversationDictationForegroundLease(stopDurableSession, ::conversationDictationDiagnostic)
    internal val completion = ConversationDictationCompletionOwner(readDraft, writeDraft, completionWrite)
    internal val parkedRecoveries = mutableStateMapOf<ConversationDictationKey, ParkedRecovery>()

    internal fun hasParkedComposerAudio(
        accountRef: String,
        groupIdHex: String,
    ): Boolean = ConversationDictationKey.from(accountRef, groupIdHex) in parkedRecoveries

    internal data class ParkedRecovery(
        val failure: ConversationDictationState.Failed,
        val audio: ConversationDictationParkedAudio,
        val draft: ConversationDictationDraftRecovery,
        val actionGeneration: Long,
        val recognition: ConversationDictationRecognitionCoordinator.Snapshot,
    )

    internal var draftTargetRemoved = false
    internal var recoveryTimeoutHandle: ConversationDictationTimeoutHandle? = null
    internal var recoveryDeadlineElapsedMillis: Long? = null
    internal var recoveryProtectionExpired = false
    internal var pendingForegroundRecoverySessionId: Long? = null
    internal var microphoneSettingsRecovery: DictationMicrophoneRecovery? = null
    internal var microphoneHostReady: () -> Boolean = { true }
    internal var microphoneOriginValid: () -> Boolean = { true }
    var foregroundMicrophoneRequired by mutableStateOf(true)
        internal set
    var notificationActionGeneration by mutableLongStateOf(0L)
        internal set
    var foregroundRefreshRevision by mutableLongStateOf(0L)
        private set
    internal val recognition = ConversationDictationRecognitionCoordinator()
    internal val generation = ConversationDictationGenerationOwner(recognitionDeadlines::cancelGeneration)
    internal val restart = ConversationDictationRestartOwner()
    internal val accumulatedTranscript: String get() = recognition.transcript

    internal var retainedAudioFailure: ConversationDictationFailure? = null
    var recoveryHandedToComposer by mutableStateOf(false)
        internal set
    val completionControlsRequired: Boolean
        get() = state.requiresCompletionControls(recoveryHandedToComposer)
    internal var finishRequested by mutableStateOf(false)
    internal var captureDeadlineElapsedMillis: Long? = null
    internal val requestedDeliveryMode: ConversationDictationDeliveryMode?
        get() = completion.intent.mode
    internal val explicitSendRequested: Boolean
        get() = completion.intent.explicit && requestedDeliveryMode == ConversationDictationDeliveryMode.SendOnFinish

    // Entry points, recognition callbacks and timeout callbacks are main-thread confined.
    internal var unresolvedRecognitionFailure: ConversationDictationFailure? = null
    internal var silenceDeadlineElapsedMillis: Long? = null

    internal val permissionRequestIdState = mutableLongStateOf(0L)
    val permissionRequestId: Long
        get() = permissionRequestIdState.longValue
    private var claimedPermissionRequestId = 0L

    internal val providerActivityRequestIdState = mutableLongStateOf(0L)
    val providerActivityRequestId: Long
        get() = providerActivityRequestIdState.longValue

    val hasPendingSession: Boolean
        get() = state !is ConversationDictationState.Idle

    val blocksNewRequest: Boolean
        get() =
            state !is ConversationDictationState.Idle &&
                state !is ConversationDictationState.Failed

    val ownsMicrophone: Boolean
        get() = microphone.held

    val hasDurableSession: Boolean
        get() = foregroundLease.active

    val foregroundServiceType: Int
        get() =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (foregroundMicrophoneRequired) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0

    /** Opaque process-and-session identity; delayed notification taps cannot target a later draft. */
    val notificationSessionToken: String?
        get() =
            foregroundLease.deferredReleaseToken
                ?: state.sessionId?.let { "$notificationInstanceId:${foregroundLease.generation}:$it" }

    val deliveryInProgress: Boolean
        get() = completion.dispatch.dispatchedSessionId?.let { it == state.sessionId } == true

    /** True while an app-owned recognizer generation is starting, listening, or transcribing. */
    internal val inActiveRecognitionState: Boolean
        get() =
            state is ConversationDictationState.Starting ||
                state is ConversationDictationState.Listening ||
                state is ConversationDictationState.Processing

    /** True while provider segment processing remains part of an open capture. */
    val captureInProgress: Boolean
        get() = !finishRequested && inActiveRecognitionState

    /** The completion button that owns indeterminate work for the current visible phase. */
    val processingDeliveryMode: ConversationDictationDeliveryMode?
        get() =
            if (finishRequested && inActiveRecognitionState) {
                requestedDeliveryMode ?: ConversationDictationDeliveryMode.PasteIntoDraft
            } else {
                null
            }

    /** Whether recovery can retranscribe sealed audio without reopening capture. */
    val canRetryRetainedAudio: Boolean
        get() =
            state is ConversationDictationState.Failed &&
                finishRequested &&
                runCatching(platform::callerAudioHasPending).getOrDefault(false)

    /** Editor changes do not turn previously saved text into unsaved recovery work. */
    val hasUnrecoveredTranscript: Boolean
        get() = (state as? ConversationDictationState.Failed)?.let { hasUnrecoveredTranscript(it) } == true

    /** Checks the displayed failure's save receipt, including while a new UI frame is pending. */
    fun hasUnrecoveredTranscript(failed: ConversationDictationState.Failed): Boolean =
        !failed.retainedTranscript.isNullOrBlank() &&
            !completion.draftRecovery.hasSavedTranscript(failed.sessionId, failed.target, failed.retainedTranscript)

    /** Retry Send cannot consume a draft that changed after automatic recovery. */
    val canRetryRecoveredSend: Boolean
        get() {
            val failed = state as? ConversationDictationState.Failed ?: return false
            return completion.draftRecovery.sendTarget(failed.sessionId, failed.target) != null
        }

    /** The speech service package a provider failure's recovery action has to open. */
    val speechProviderPackage: String?
        get() = runCatching(platform::speechProviderPackage).getOrNull()

    val completionActionsEnabled: Boolean
        get() = !deliveryInProgress && completion.intent.canChooseExplicit && hasPendingCompletion

    private val hasPendingCompletion: Boolean
        get() =
            inActiveRecognitionState &&
                (
                    generation.activeId != null ||
                        state is ConversationDictationState.Starting ||
                        finishRequested
                )

    /** Returns the completion revision used by Compose consumers to observe a terminal write. */
    fun completionRevision(
        accountRef: String,
        groupIdHex: String,
    ): Int = completionRevisions[ConversationDictationKey.from(accountRef, groupIdHex)] ?: 0

    /** Returns the revision of the latest dictated send whose pending row entered the timeline. */
    fun pendingSendRevision(
        accountRef: String,
        groupIdHex: String,
    ): Int = pendingSendRevisions[ConversationDictationKey.from(accountRef, groupIdHex)] ?: 0

    /** Whether the current immutable target belongs to the supplied conversation. */
    fun isOwnedBy(
        accountRef: String,
        groupIdHex: String,
    ): Boolean = state.target?.matchesConversation(accountRef, groupIdHex) == true

    /** Rechecks capture and recovery ownership for both composer layouts and every Dictate gesture. */
    internal fun composerAccess(
        accountRef: String,
        groupIdHex: String,
    ): ConversationDictationComposerAccess {
        val current = state
        val failed = current as? ConversationDictationState.Failed
        parkedComposerAccess(accountRef, groupIdHex)?.let { return it }
        val phase =
            when {
                current.target != null && !isOwnedBy(accountRef, groupIdHex) ->
                    if (failed != null &&
                        failedComposerPhase(failed) == ConversationDictationComposerPhase.Ready &&
                        runCatching { targetAvailable(accountRef, groupIdHex) }.getOrDefault(false)
                    ) {
                        ConversationDictationComposerPhase.Ready
                    } else {
                        ConversationDictationComposerPhase.OtherConversation
                    }
                foregroundLease.releaseDeferred -> ConversationDictationComposerPhase.ClosingMicrophone
                failed != null -> failedComposerPhase(failed)
                recoveryHandedToComposer -> ConversationDictationComposerPhase.Transcribing
                current !is ConversationDictationState.Idle -> ConversationDictationComposerPhase.Protected
                !runCatching { targetAvailable(accountRef, groupIdHex) }.getOrDefault(false) ->
                    ConversationDictationComposerPhase.TargetUnavailable
                else -> ConversationDictationComposerPhase.Ready
            }
        return ConversationDictationComposerAccess(
            current.sessionId,
            notificationActionGeneration,
            phase,
            failed?.cause ?: failed?.reason,
        )
    }

    /** No queued restart: resolution only makes the next explicit Dictate gesture eligible. */
    internal fun requestComposerStart(
        accountRef: String,
        groupIdHex: String,
        draft: TextFieldValue,
        replyToMessageIdHex: String?,
    ): ConversationDictationComposerAccess {
        expireRetainedRecoveryIfDue()
        val access = composerAccess(accountRef, groupIdHex)
        if (access.phase != ConversationDictationComposerPhase.Ready) return access
        val started = requestStart(accountRef, groupIdHex, draft, replyToMessageIdHex)
        val rejected = composerAccess(accountRef, groupIdHex)
        return when {
            started -> access
            rejected.phase == ConversationDictationComposerPhase.Ready ->
                rejected.copy(
                    phase = ConversationDictationComposerPhase.Protected,
                    failure = ConversationDictationFailure.Unknown,
                )
            else -> rejected
        }
    }

    /** An explicit fresh Dictate gesture never opens a retained-audio decision dialog. */
    internal fun requestFreshComposerStart(
        accountRef: String,
        groupIdHex: String,
        draft: TextFieldValue,
        replyToMessageIdHex: String?,
    ): ConversationDictationComposerAccess {
        if (!runCatching { targetAvailable(accountRef, groupIdHex) }.getOrDefault(false)) {
            return composerAccess(accountRef, groupIdHex).copy(
                phase = ConversationDictationComposerPhase.TargetUnavailable,
            )
        }
        val previousRevision = completionRevision(accountRef, groupIdHex)
        (state as? ConversationDictationState.Failed)
            ?.takeIf { it.target.matchesConversation(accountRef, groupIdHex) }
            ?.let { finishFailedToDraft(it.sessionId) }
        val startingDraft =
            if (completionRevision(accountRef, groupIdHex) != previousRevision) {
                runCatching { readDraft(accountRef, groupIdHex).value }.getOrNull()
            } else {
                draft
            }
        return startingDraft?.let { value ->
            if (state is ConversationDictationState.Idle && !foregroundMicrophoneRequired) {
                val key = ConversationDictationKey.from(accountRef, groupIdHex)
                parkedRecoveries[key]?.let { parked ->
                    if (runCatching { parked.audio.discard() }.isSuccess) parkedRecoveries.remove(key)
                }
            }
            requestComposerStart(accountRef, groupIdHex, value, replyToMessageIdHex)
        } ?: composerAccess(accountRef, groupIdHex)
    }

    /** Paste recovered text or end an empty attempt; never treat an uncertain send as cancelled. */
    internal fun finishFailedToDraft(sessionId: Long) {
        val failed = (state as? ConversationDictationState.Failed)?.takeIf { it.sessionId == sessionId } ?: return
        if (failed.reason == ConversationDictationFailure.DeliveryUnknown || deliveryInProgress) return
        val recovery = recoverRecognizedDraftResult(sessionId, failed.target, failed.retainedTranscript)
        if (state !== failed) return
        val textSafe =
            failed.retainedTranscript.isNullOrBlank() ||
                recovery.draftRecovered ||
                recovery == ConversationDictationDraftRecovery.Result.Superseded
        val captureSafe =
            !foregroundMicrophoneRequired &&
                runCatching(platform::callerAudioHasPending).getOrNull() != null
        if (textSafe && captureSafe) cancelSession()
    }

    /** Recovery actions are enabled only for the displayed current or parked recording. */
    internal fun canRetryComposerAudio(access: ConversationDictationComposerAccess): Boolean {
        if (composerRecovery(access) != null) return canRetryRetainedAudio
        if (state !is ConversationDictationState.Idle || foregroundMicrophoneRequired) return false
        return parkedRecoveries.values.any {
            it.failure.sessionId == access.sessionId &&
                it.actionGeneration == access.actionGeneration &&
                runCatching { targetAvailable(it.failure.target) }.getOrDefault(false)
        }
    }

    /** Composer audio recovery is draft-only, including recordings explicitly kept for later. */
    internal fun retryComposerAudio(access: ConversationDictationComposerAccess) {
        if (restoreParkedRecovery(access)) {
            if (canRetryRetainedAudio) retry()
            return
        }
        composerRecovery(access)?.let {
            if (canRetryRetainedAudio) {
                completion.intent.reset()
                completion.intent.choose(ConversationDictationDeliveryMode.PasteIntoDraft)
                retry()
            }
        }
    }

    /** Only a confirmed, current receipt can discard PCM; saved composer edits are never rewritten. */
    internal fun discardComposerAudio(access: ConversationDictationComposerAccess) {
        val parked =
            parkedRecoveries.entries.firstOrNull {
                it.value.failure.sessionId == access.sessionId && it.value.actionGeneration == access.actionGeneration
            }
        if (parked != null) {
            parkedRecoveries.remove(parked.key)?.audio?.discard()
            return
        }
        composerRecovery(access)?.let { cancelSession() }
    }

    /** Keeps closed audio under its origin while releasing the active session for another recording. */
    @Suppress("ReturnCount") // Refuse each unsafe transfer before changing either owner.
    internal fun keepComposerAudioForLater(access: ConversationDictationComposerAccess): Boolean {
        val failed = state as? ConversationDictationState.Failed ?: return false
        val ownsFailure =
            access.sessionId == failed.sessionId && access.actionGeneration == notificationActionGeneration
        val safeToKeep =
            !foregroundMicrophoneRequired &&
                failed.reason != ConversationDictationFailure.DeliveryUnknown &&
                !hasUnrecoveredTranscript
        if (!ownsFailure || !safeToKeep) return false
        val key = ConversationDictationKey.from(failed.target.accountRef, failed.target.groupIdHex)
        val pendingAudio = runCatching(platform::callerAudioHasPending).getOrNull() ?: return false
        if (!pendingAudio) {
            cancelSession()
            return true
        }
        if (parkedRecoveries.size >= MAX_PARKED_RECOVERIES || key in parkedRecoveries) return false
        val audio = runCatching(platform::parkCallerAudio).getOrNull() ?: return false
        parkedRecoveries[key] =
            ParkedRecovery(
                failed,
                audio,
                completion.parkDraftRecovery(),
                notificationActionGeneration,
                recognition.snapshot(),
            )
        retainedAudioFailure = null
        clearRecognitionSession(cancel = false)
        resetTranscriptSession()
        recoveryHandedToComposer = false
        state = ConversationDictationState.Idle
        return true
    }

    /** Starts an app-owned, service-backed dictation session for the captured draft. */
    fun requestStart(
        accountRef: String,
        groupIdHex: String,
        draft: TextFieldValue,
        replyToMessageIdHex: String? = null,
    ): Boolean =
        requestStart(
            accountRef = accountRef,
            groupIdHex = groupIdHex,
            draft = draft,
            replyToMessageIdHex = replyToMessageIdHex,
            mode = ConversationDictationMode.InApp,
        )

    /** Starts the compatibility flow that delegates microphone ownership to a provider Activity. */
    fun requestProviderActivityStart(
        accountRef: String,
        groupIdHex: String,
        draft: TextFieldValue,
        replyToMessageIdHex: String? = null,
    ): Boolean =
        requestStart(
            accountRef = accountRef,
            groupIdHex = groupIdHex,
            draft = draft,
            replyToMessageIdHex = replyToMessageIdHex,
            mode = ConversationDictationMode.ProviderActivity,
        )

    /** Captures one immutable target and replaces any safely replaceable prior session. */
    private fun requestStart(
        accountRef: String,
        groupIdHex: String,
        draft: TextFieldValue,
        replyToMessageIdHex: String?,
        mode: ConversationDictationMode,
    ): Boolean {
        expireRetainedRecoveryIfDue()
        conversationDictationDiagnostic("event=request_start mode=${mode.name}")
        if (!canBeginCapture(accountRef, groupIdHex)) return false
        // Unlike the app-owned SpeechRecognizer, an external provider Activity
        // cannot be synchronously terminated by this controller. Keep its one
        // ActivityResult owner stable until the provider returns, so a result
        // can never be misattributed to a replacement target.
        if (rejectsExistingSession(accountRef, groupIdHex, mode)) return false

        // A request for a different target/mode is an explicit replacement.
        // Tear down the previous generation before publishing the new target;
        // all callbacks from the previous generation then fail the session-id
        // ownership check and become no-ops.
        clearMicrophoneRecovery()
        retainedAudioFailure = null
        recoveryHandedToComposer = false
        clearRecognitionSession(cancel = true)
        completion.draftRecovery.reset()
        draftTargetRemoved = false
        resetTranscriptSession()
        if (!beginCaptureOwnership()) return false
        if (state.sessionId != null) conversationDictationDiagnostic("event=session_finished outcome=replaced")
        val sessionId = ++nextSessionId
        val capturedRevision = readDraft(accountRef, groupIdHex).revision
        val target =
            ConversationDictationTarget(
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                replyToMessageIdHex = replyToMessageIdHex,
                capturedDraft = draft.copy(composition = null),
                capturedDraftRevision = capturedRevision,
                mode = mode,
                finishAfterSilenceMillis = finishAfterSilenceMillis()?.takeIf { it > 0L },
                silenceDeliveryMode = silenceDeliveryMode(),
                pauseOtherAudio = pauseOtherAudio(),
            )
        if (!targetAvailable(target)) {
            conversationDictationDiagnostic("event=request_start accepted=false reason=reply_unavailable")
            return false
        }
        DictationDiagnostics.activeSession = sessionId
        DictationDiagnostics.record("event=session_started session=$sessionId mode=${mode.name}")
        if (!runCatching(platform::prepareProviderSelection).getOrDefault(false)) {
            state = ConversationDictationState.ProviderSelectionRequired(sessionId, target)
            return true
        }
        val usesOfflineSpeechToText = speechProviderPackage == OFFLINE_SPEECH_TO_TEXT_PACKAGE
        val disclosureAcceptedForProvider =
            if (usesOfflineSpeechToText) offlineDisclosureAccepted() else disclosureAccepted()
        if (!disclosureAcceptedForProvider) {
            state = ConversationDictationState.DisclosureRequired(sessionId, target, usesOfflineSpeechToText)
            return true
        }
        startTarget(sessionId, target)
        return true
    }

    /**
     * Reply identity is part of the immutable origin, not mutable composer decoration. A mounted controller must
     * still match both null and non-null reply origins. With no mounted controller, only a non-reply session may
     * continue: a real reply's composer context is not durable, and pasting it as standalone text would retarget it.
     */
    internal fun targetAvailable(target: ConversationDictationTarget): Boolean {
        if (!targetAvailable(target.accountRef, target.groupIdHex)) return false
        return when (
            targetReplyAvailable(target.accountRef, target.groupIdHex, target.replyToMessageIdHex)
        ) {
            true -> true
            false -> false
            null -> target.replyToMessageIdHex == null
        }
    }

    /** The choice applies on the next gesture, never as a side effect of selecting a row. */
    fun onProviderSelected() {
        if (state is ConversationDictationState.ProviderSelectionRequired) cancel()
    }

    fun providerActivityIntent(): Intent = platform.providerActivityIntent()

    /** Records the first-use disclosure and resumes its exact pending target. */
    fun acceptDisclosure() {
        val pending = state as? ConversationDictationState.DisclosureRequired ?: return
        if (pending.usesOfflineSpeechToText) {
            markOfflineDisclosureAccepted()
        } else {
            markDisclosureAccepted()
        }
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
        resumeAfterMicrophonePermission(pending)
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
    fun paste() {
        expireRetainedRecoveryIfDue()
        val failed = state as? ConversationDictationState.Failed
        if (recoveryHandedToComposer && canRetryRetainedAudio) return
        if (failed != null &&
            failed.reason != ConversationDictationFailure.DeliveryUnknown &&
            !failed.retainedTranscript.isNullOrBlank()
        ) {
            deliverRetainedTranscript(failed, ConversationDictationDeliveryMode.PasteIntoDraft)
        } else {
            stopWithDeliveryMode(ConversationDictationDeliveryMode.PasteIntoDraft)
        }
    }

    /** An explicit confirmation may send only the recognized prefix instead of retrying failed PCM. */
    fun sendRecognizedText() {
        expireRetainedRecoveryIfDue()
        val failed = state as? ConversationDictationState.Failed ?: return
        if (foregroundMicrophoneRequired || (recoveryHandedToComposer && !canRetryRecoveredSend)) return
        if (failed.reason != ConversationDictationFailure.SendBlocked ||
            failed.retainedTranscript.isNullOrBlank()
        ) {
            return
        }
        deliverRetainedTranscript(failed, ConversationDictationDeliveryMode.SendOnFinish)
    }

    /** Stops recognition and sends only after the existing origin/draft safety checks pass. */
    fun send() = stopWithDeliveryMode(ConversationDictationDeliveryMode.SendOnFinish)

    /** Discards process-memory transcript state and releases every resource held by the session. */
    fun cancel() {
        // Once MDK dispatch starts, cancellation cannot recall the message. Retain ownership until its outcome.
        if (deliveryInProgress) {
            conversationDictationDiagnostic(
                "event=completion_action action=Cancel accepted=false " +
                    "reason=delivery_in_progress",
            )
            return
        }
        conversationDictationDiagnostic(
            "event=completion_action action=Cancel accepted=true has_text=${accumulatedTranscript.isNotBlank()}",
        )
        cancelSession()
    }

    /**
     * Involuntary focus loss preserves input as a draft, including after automatic silence completion.
     * After an explicit Send or Paste, capture is already sealed, so the chosen action continues under
     * its own drain deadline. This deliberately narrows #3401 to choices the user did not make.
     */
    fun interruptForAudioFocusLoss() {
        if (!inActiveRecognitionState || deliveryInProgress || completion.intent.committed) return
        if (finishRequested && completion.intent.explicit) {
            // Another app's audio cannot change the sealed speech, so finish the user's chosen action.
            conversationDictationDiagnostic("event=audio_focus_lost_after_finish action=continued")
            return
        }
        completion.validation.reset()
        completion.dispatch.cancelBeforeTransport()
        completion.intent.reset()
        completion.intent.choose(ConversationDictationDeliveryMode.PasteIntoDraft)
        unresolvedRecognitionFailure = ConversationDictationFailure.Interrupted
        abortSessionPreservingTranscript("audio_focus_lost")
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
    fun onProviderActivityResult(
        transcript: String?,
        requestId: Long = providerActivityRequestId,
    ) {
        if (requestId != providerActivityRequestId) return
        conversationDictationDiagnostic("event=provider_activity_result has_text=${!transcript.isNullOrBlank()}")
        val active = state as? ConversationDictationState.ProviderActivityActive ?: return
        if (!runCatching(platform::pinnedProviderStillAvailable).getOrDefault(false)) {
            fail(active.sessionId, active.target, ConversationDictationFailure.ProviderUnavailable)
            return
        }
        val recognized = transcript?.trim().orEmpty()
        if (recognized.isBlank()) {
            fail(active.sessionId, active.target, ConversationDictationFailure.NoSpeech)
            return
        }
        val localValidation = completedTargetValidation(active.target)
        conversationDictationDiagnostic("event=target_validation phase=provider_result result=${localValidation.name}")
        if (localValidation != ConversationDictationTargetValidation.Available) {
            fail(
                active.sessionId,
                active.target,
                ConversationDictationFailure.Unknown,
                captureEnd = DictationFailureCapture.Cancel,
                retainedTranscript = recognized,
            )
            return
        }
        validateAndDeliverTranscript(active.sessionId, active.target, recognized)
    }

    /** Clears ownership after the user dismisses the provider-owned Activity. */
    fun onProviderActivityCancelled(requestId: Long = providerActivityRequestId) {
        if (requestId != providerActivityRequestId) return
        conversationDictationDiagnostic("event=provider_activity_cancelled")
        if (state is ConversationDictationState.ProviderActivityActive) cancel()
    }

    /** Converts a provider-Activity launch failure into a retryable terminal state. */
    fun onProviderActivityLaunchFailed(requestId: Long = providerActivityRequestId) {
        if (requestId != providerActivityRequestId) return
        conversationDictationDiagnostic("event=provider_activity_launch_failed")
        val providerState = state as? ConversationDictationState.ProviderActivityActive ?: return
        fail(
            providerState.sessionId,
            providerState.target,
            ConversationDictationFailure.ProviderUnavailable,
        )
    }

    /** Dismisses a terminal failure and discards any caller audio retained for an explicit retry. */
    fun dismissFailure() {
        if (state is ConversationDictationState.Failed) cancelSession()
    }

    /** Arms one return from settings only for the failure whose recovery the user chose. */
    fun resumeAfterMicrophoneSettings() = armMicrophoneSettingsRecovery()

    /** Recreates a failed session against the origin's current authoritative draft. */
    fun retry() {
        expireRetainedRecoveryIfDue()
        val failed = state as? ConversationDictationState.Failed
        if (failed == null) {
            conversationDictationDiagnostic("event=retry accepted=false reason=not_failed")
            return
        }
        conversationDictationDiagnostic(
            "event=retry requested=true failure=${failed.reason.name} " +
                "finish_requested=$finishRequested retained_transcript=${!failed.retainedTranscript.isNullOrBlank()}",
        )
        if (failed.reason == ConversationDictationFailure.DeliveryUnknown) {
            conversationDictationDiagnostic("event=retry accepted=false reason=delivery_unknown")
            return
        }
        val pendingAudio = runCatching(platform::callerAudioHasPending).getOrNull() ?: return
        if (recoveryHandedToComposer) {
            if (!pendingAudio) return
            completion.intent.reset()
            completion.intent.choose(ConversationDictationDeliveryMode.PasteIntoDraft)
        }
        if (retainSupersededSendDraft(failed, pendingAudio)) return
        recoveryTimeoutHandle?.cancel()
        recoveryTimeoutHandle = null
        recoveryDeadlineElapsedMillis = null
        recoveryProtectionExpired = false
        pendingForegroundRecoverySessionId = null
        notificationActionGeneration += 1L
        if (finishRequested && pendingAudio) {
            retryRetainedCallerAudio(failed)
            return
        }
        failed.retainedTranscript?.takeIf(String::isNotBlank)?.let { transcript ->
            retryRetainedTranscript(failed, transcript)
            return
        }
        val accepted =
            requestStart(
                accountRef = failed.target.accountRef,
                groupIdHex = failed.target.groupIdHex,
                draft = readDraft(failed.target.accountRef, failed.target.groupIdHex).value,
                replyToMessageIdHex = failed.target.replyToMessageIdHex,
                mode = failed.target.mode,
            )
        conversationDictationDiagnostic("event=retry path=fresh_start accepted=$accepted")
    }

    /** Cancels the session if its exact origin conversation was removed. */
    fun onTargetRemoved(
        accountRef: String,
        groupIdHex: String,
    ) {
        parkedRecoveries.remove(ConversationDictationKey.from(accountRef, groupIdHex))?.audio?.discard()
        if (!isOwnedBy(accountRef, groupIdHex)) return
        draftTargetRemoved = true
        val current = state
        val sessionId = current.sessionId
        val target = current.target
        val transcript =
            (current as? ConversationDictationState.Failed)
                ?.retainedTranscript
                .orEmpty()
                .ifBlank { accumulatedTranscript.trim().ifBlank { completion.transcript.trim() } }
        if (transcript.isNotBlank() && sessionId != null && target != null) {
            conversationDictationDiagnostic("event=target_removed outcome=transcript_retained")
            if (current !is ConversationDictationState.Failed) {
                fail(
                    sessionId,
                    target,
                    ConversationDictationFailure.Unknown,
                    captureEnd = DictationFailureCapture.Cancel,
                    retainedTranscript = transcript,
                )
            }
        } else {
            conversationDictationDiagnostic("event=target_removed outcome=session_cancelled")
            cancelSession()
        }
    }

    /** Cancels only when the target account is signed out or removed, not when it becomes inactive. */
    fun onAccountUnavailable(accountRef: String) {
        parkedRecoveries.keys.filter { it.accountRef.equals(accountRef, ignoreCase = true) }.forEach { key ->
            parkedRecoveries.remove(key)?.audio?.discard()
        }
        if (state.target?.accountRef?.equals(accountRef, ignoreCase = true) == true) {
            conversationDictationDiagnostic("event=account_unavailable outcome=cancelled")
            cancelSession()
        }
    }

    /** Releases any provider/microphone resource without discarding retained transcript text. */
    fun onAppBackgrounded() {
        pendingForegroundRecoverySessionId = null
        onMicrophoneBackgrounded()
        val abort =
            when (state) {
                is ConversationDictationState.ProviderSelectionRequired,
                is ConversationDictationState.DisclosureRequired,
                is ConversationDictationState.PermissionRequired,
                is ConversationDictationState.CheckingProvider,
                -> true
                is ConversationDictationState.Starting,
                is ConversationDictationState.Listening,
                is ConversationDictationState.Processing,
                -> !foregroundLease.active
                // The external provider Activity remains its callback's owner while White Noise is backgrounded.
                else -> false
            }
        conversationDictationDiagnostic(
            "event=app_visibility foreground=false durable=${foregroundLease.active} " +
                "phase=${state.diagnosticPhase()} outcome=${if (abort) "aborted" else "continued"}",
        )
        if (abort) abortSessionPreservingTranscript("app_background")
    }

    /** Retries any deferred narrowing only after a real foreground return. */
    fun onAppForegrounded() {
        if (resumeMicrophoneSettingsRecovery()) return
        pendingForegroundRecoverySessionId = (state as? ConversationDictationState.Failed)?.sessionId
        expireRetainedRecoveryIfDue()
        val failed = state as? ConversationDictationState.Failed
        if (failed?.cause == ConversationDictationFailure.DraftWriteDeferred ||
            failed?.cause == ConversationDictationFailure.DraftWriteRefused
        ) {
            return
        }
        if (failed?.reason == ConversationDictationFailure.DraftWriteDeferred) {
            retryForegroundDeferredDraft(failed)
            return
        }
        if (failed != null && !failed.draftRecovered) {
            val recovery = recoverRecognizedDraftResult(failed.sessionId, failed.target, failed.retainedTranscript)
            if (state !== failed) return
            state =
                failed.copy(
                    draftRecovered = recovery.draftRecovered,
                    draftSuperseded = recovery == ConversationDictationDraftRecovery.Result.Superseded,
                )
        }
        (state as? ConversationDictationState.Failed)?.let(this::handRecoveryToComposer)
        val savedFailure = state is ConversationDictationState.Failed && !hasUnrecoveredTranscript
        if (savedFailure &&
            !foregroundMicrophoneRequired &&
            !runCatching(platform::callerAudioHasPending).getOrDefault(true)
        ) {
            // The foreground composer now shows the failure; until this return keep its Open-app card.
            releaseDurableSessionLease()
        }
        if (foregroundLease.active) {
            foregroundRefreshRevision += 1L
        } else {
            pendingForegroundRecoverySessionId = (state as? ConversationDictationState.Failed)?.sessionId
            reattachForegroundRecoveryAfterClosure()
        }
    }

    /** Keeps service-backed capture alive when the UI task is removed from recents. */
    fun onTaskRemoved() {
        pendingForegroundRecoverySessionId = null
        val continues = foregroundLease.active || state is ConversationDictationState.Failed
        conversationDictationDiagnostic(
            "event=task_removed durable=${foregroundLease.active} outcome=${if (continues) "continued" else "aborted"}",
        )
        if (!continues) abortSessionPreservingTranscript("task_removed")
    }

    /** Cancels capture if Android destroys the service that makes background ownership explicit. */
    fun onDurableServiceDestroyed(sessionToken: String) {
        val owned = foregroundLease.active && notificationSessionToken == sessionToken
        val callbackSession = sessionToken.substringAfterLast(':').toLongOrNull() ?: 0L
        conversationDictationDiagnostic(
            "event=service_ownership callback_session=$callbackSession durable=${foregroundLease.active} " +
                "outcome=${if (owned) "aborted" else "stale"}",
        )
        if (!owned) return
        foregroundLease.onServiceDestroyed()
        if (state is ConversationDictationState.Failed) return
        if (finishRequested && runCatching(platform::callerAudioHasPending).getOrDefault(false)) {
            failWithRetainedCallerAudio(
                requireNotNull(state.sessionId),
                requireNotNull(state.target),
                ConversationDictationFailure.Unknown,
            )
            return
        }
        abortSessionPreservingTranscript("service_destroyed")
    }

    /** Starts capture only after this session's foreground promotion and enqueue are both confirmed. */
    @Suppress(
        "ComplexCondition",
        "CyclomaticComplexMethod",
        "ReturnCount",
    )
    fun onDurableServiceReady(sessionToken: String) {
        if (state is ConversationDictationState.Failed &&
            foregroundLease.active &&
            notificationSessionToken == sessionToken
        ) {
            if (foregroundLease.notePromotion()) foregroundLease.markReady()
            return
        }
        val current = state as? ConversationDictationState.Starting ?: return
        val retryingSealedAudio = finishRequested && runCatching(platform::callerAudioHasPending).getOrDefault(false)
        if (
            !foregroundLease.active ||
            foregroundLease.ready ||
            notificationSessionToken != sessionToken ||
            (finishRequested && !retryingSealedAudio)
        ) {
            return
        }
        if (!foregroundLease.notePromotion()) return
        try {
            if (!targetAvailable(current.target)) {
                if (ownsDurableSession(current.sessionId, sessionToken)) cancel()
                return
            }
            if (retryingSealedAudio) {
                acknowledgeSealedAudioReady(current, sessionToken)
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
                failDurableSessionStart(current.sessionId, current.target, ConversationDictationFailure.Unknown)
            }
            return
        }
        if (!ownsDurableSession(current.sessionId, sessionToken) || finishRequested) return
        acknowledgeDurableServiceReady(current.sessionId, current.target)
    }

    /** A rejected promotion is recoverable but must not silently open another recording surface. */
    fun onDurableServiceStartFailed(sessionToken: String) {
        val current = state
        if (!foregroundLease.active || foregroundLease.ready || notificationSessionToken != sessionToken) return
        failDurableSessionStart(
            requireNotNull(current.sessionId),
            requireNotNull(current.target),
            ConversationDictationFailure.Unknown,
        )
    }

    /** An explicit finish can still use committed text during a transient reconnect. */
    internal val ConversationDictationFailure.shouldPreserveTranscript: Boolean
        get() =
            !isEmptyRecognition &&
                this != ConversationDictationFailure.ProviderDisconnected

    internal val ConversationDictationFailure.canRetryRetainedCallerAudio: Boolean
        get() =
            isEmptyRecognition ||
                this == ConversationDictationFailure.ProviderDisconnected ||
                this == ConversationDictationFailure.Network ||
                this == ConversationDictationFailure.RecognizerBusy ||
                this == ConversationDictationFailure.Unknown

    internal val ConversationDictationFailure.hasProviderCapacityBackoff: Boolean
        get() =
            this == ConversationDictationFailure.ProviderDisconnected ||
                this == ConversationDictationFailure.RecognizerBusy

    /** Uses the provider-disconnect backoff for failures that otherwise form a capacity hot loop. */
    internal fun ConversationDictationFailure.retainedCallerAudioRetryDelayMillis(attempt: Int): Long =
        if (hasProviderCapacityBackoff) {
            PROVIDER_DISCONNECT_RETRY_DELAYS_MILLIS[
                (attempt - 1).coerceIn(0, PROVIDER_DISCONNECT_RETRY_DELAYS_MILLIS.lastIndex),
            ]
        } else {
            GENERATION_RESTART_DELAY_MILLIS
        }

    internal companion object {
        const val MAX_PARKED_RECOVERIES = 4
        const val PREFERENCES_NAME = CONVERSATION_DICTATION_PREFERENCES_NAME
        const val DISCLOSURE_ACCEPTED_KEY = "composer_dictation_external_provider_disclosed_v2"
        const val OFFLINE_DISCLOSURE_ACCEPTED_KEY = "composer_dictation_offline_provider_disclosed_v2"
        const val STARTING_TIMEOUT_MILLIS = 10_000L
        const val FOREGROUND_READINESS_TIMEOUT_MILLIS = 3_000L
        const val RETAINED_RECOVERY_TIMEOUT_MILLIS = 30L * 60L * 1_000L
        const val PROVIDER_READINESS_TIMEOUT_MILLIS = 1_500L

        /**
         * Safety bound for a platform probe that never answers. It outlives the platform's own
         * probe timeout so a real answer, including its inconclusive one, always wins.
         */
        const val CALLER_AUDIO_PROBE_TIMEOUT_MILLIS = 6_000L
        const val MAX_SESSION_MILLIS = 65L * 60L * 1_000L
        const val CALLER_AUDIO_DRAIN_TIMEOUT_MILLIS = 90_000L

        // Recorder tail reads are bounded at 750 ms; a stalled closure cannot retain foreground forever.
        const val CALLER_AUDIO_FAILURE_CLOSE_TIMEOUT_MILLIS = 1_000L
        const val PROCESSING_TIMEOUT_MILLIS = 20_000L
        const val ORDINARY_SILENCE_MILLIS = 2_000L
        const val MAX_CONSECUTIVE_RAPID_EMPTY_GENERATIONS = 3
        const val MAX_RETAINED_CALLER_AUDIO_RETRIES = 2
        const val GENERATION_RESTART_DELAY_MILLIS = 500L
        const val SUCCESS_RESULT_RESTART_DELAY_MILLIS = 500L
        const val PERMISSION_RETRY_DELAY_MILLIS = 500L
        val PROVIDER_DISCONNECT_RETRY_DELAYS_MILLIS = longArrayOf(500L, 1_000L, 2_000L)
    }
}

internal sealed interface ConversationDictationMerge {
    data class Applied(
        val value: TextFieldValue,
    ) : ConversationDictationMerge

    data object NeedsAppendFallback : ConversationDictationMerge
}

/**
 * Merges a recognized segment at the captured selection when concurrent draft edits can be
 * remapped unambiguously; otherwise asks the caller to append against the latest draft snapshot.
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
            ?: return ConversationDictationMerge.NeedsAppendFallback
    return ConversationDictationMerge.Applied(
        insertConversationDictationTranscript(current, remappedSelection, recognized),
    )
}

/**
 * Re-anchors a dictation on the draft as it stands now, with the transcript's insertion point remapped
 * exactly as [mergeConversationDictationTranscript] would place it, or at the end when that is ambiguous.
 */
internal fun ConversationDictationTarget.rebasedOnto(current: ConversationDictationDraftSnapshot) =
    when {
        current.revision == capturedDraftRevision && current.value.text == capturedDraft.text -> this
        current.value.text == capturedDraft.text -> copy(capturedDraftRevision = current.revision)
        else -> {
            val selection =
                remapConversationDictationSelection(capturedDraft, current.value)
                    ?: TextRange(current.value.text.length)
            copy(
                capturedDraft = TextFieldValue(current.value.text, selection),
                capturedDraftRevision = current.revision,
            )
        }
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
