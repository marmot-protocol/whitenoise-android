package dev.ipf.whitenoise.android.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Owns the immutable send claim until admission, rejection or pending transport. */
internal class ConversationDictationCompletionDispatchOwner(
    private val recovery: () -> ConversationDictationDraftRecovery,
) {
    var dispatchedSessionId by mutableStateOf<Long?>(null)
        private set
    private var sendJob: Job? = null
    private var dispatchClaim: ConversationDictationDispatchClaim? = null
    private var sendBlockedNotifiedSession: Long? = null

    fun resetBlockedNotice() {
        sendBlockedNotifiedSession = null
    }

    fun shouldNotifyBlocked(sessionId: Long): Boolean {
        if (sendBlockedNotifiedSession == sessionId) return false
        sendBlockedNotifiedSession = sessionId
        return true
    }

    fun finishDispatch() {
        dispatchClaim = null
    }

    fun clearDispatchOwnership() {
        dispatchClaim = null
        dispatchedSessionId = null
    }

    internal data class DispatchContext(
        val sessionId: Long,
        val target: ConversationDictationTarget,
        val request: ConversationDictationSendRequest,
        val admissionTarget: ConversationDictationTarget,
    )

    internal data class Transport(
        val timeoutMillis: Long,
        val send: suspend (ConversationDictationSendRequest) -> Boolean,
    )

    internal data class DispatchGuards(
        val currentSessionId: () -> Long?,
        val processing: () -> Boolean,
        val emptyDraft: (ConversationDictationTarget) -> Long?,
        val restoreDraft: (ConversationDictationTarget, Long?) -> Long?,
    )

    internal data class DispatchOutcomes(
        val onPending: (ConversationDictationDispatchClaim) -> Unit,
        val onAccepted: (ConversationDictationDispatchClaim) -> Unit,
        val onRetained: (ConversationDictationDispatchClaim, Boolean?) -> Unit,
    )

    internal data class DispatchCallbacks(
        val guards: DispatchGuards,
        val outcomes: DispatchOutcomes,
    )

    fun cancelBeforeTransport() {
        dispatchClaim = null
        sendJob?.cancel()
        sendJob = null
    }

    fun reset() {
        cancelBeforeTransport()
        dispatchedSessionId = null
        sendBlockedNotifiedSession = null
    }

    /** One immutable dispatch claim owns admission, pre-transport rollback and terminal outcome. */
    fun launchDispatch(
        scope: CoroutineScope,
        context: DispatchContext,
        transport: Transport,
        callbacks: DispatchCallbacks,
    ) {
        val claim = ConversationDictationDispatchClaim()
        dispatchClaim = claim
        val guardedRequest = guardedRequest(context, callbacks.guards, callbacks.outcomes, claim)
        sendJob =
            scope.launch(start = CoroutineStart.LAZY) {
                if (callbacks.guards.currentSessionId() != context.sessionId) return@launch
                try {
                    val accepted = dispatch(transport.timeoutMillis, guardedRequest, transport.send)
                    if (dispatchClaim !== claim ||
                        callbacks.guards.currentSessionId() != context.sessionId ||
                        !callbacks.guards.processing()
                    ) {
                        return@launch
                    }
                    if (accepted == true) {
                        callbacks.outcomes.onAccepted(claim)
                    } else {
                        callbacks.outcomes.onRetained(claim, accepted)
                    }
                } finally {
                    if (dispatchClaim === claim &&
                        callbacks.guards.currentSessionId() == context.sessionId &&
                        callbacks.guards.processing()
                    ) {
                        callbacks.outcomes.onRetained(claim, null)
                    }
                }
            }
        sendJob?.start()
    }

    private fun guardedRequest(
        context: DispatchContext,
        guards: DispatchGuards,
        outcomes: DispatchOutcomes,
        claim: ConversationDictationDispatchClaim,
    ): ConversationDictationSendRequest {
        val (sessionId, target, request) = context
        val admissionTarget = context.admissionTarget
        return request.copy(
            beginDispatch = {
                if (dispatchClaim !== claim ||
                    guards.currentSessionId() != sessionId ||
                    dispatchedSessionId != null
                ) {
                    false
                } else {
                    dispatchedSessionId = sessionId
                    claim.emptiedRevision = guards.emptyDraft(admissionTarget)
                    if (claim.emptiedRevision == null) {
                        dispatchedSessionId = null
                        false
                    } else {
                        recovery().updateDispatch(sessionId, target, clearedRevision = claim.emptiedRevision)
                        true
                    }
                }
            },
            onDispatchRejectedBeforeTransport = {
                if (dispatchClaim === claim &&
                    guards.currentSessionId() == sessionId &&
                    dispatchedSessionId == sessionId
                ) {
                    conversationDictationDiagnostic("event=send_outcome outcome=rejected_before_transport")
                    val emptied = claim.emptiedRevision
                    dispatchedSessionId = null
                    claim.restoredRevision = guards.restoreDraft(admissionTarget, emptied)
                    // A temporarily unavailable host write still owes restoration of this exact clear.
                    // Retained completion transfers the revision into the existing recovery owner.
                    if (claim.restoredRevision != null) claim.emptiedRevision = null
                    recovery().updateDispatch(sessionId, target, restoredRevision = claim.restoredRevision)
                }
            },
            onPendingShown = {
                if (dispatchClaim === claim &&
                    guards.currentSessionId() == sessionId &&
                    dispatchedSessionId == sessionId
                ) {
                    // Pending transport outlives dictation teardown.
                    sendJob = null
                    outcomes.onPending(claim)
                }
            },
        )
    }

    /** A timeout or uncertain transport result never authorizes a second dispatch. */
    suspend fun dispatch(
        timeoutMillis: Long,
        request: ConversationDictationSendRequest,
        send: suspend (ConversationDictationSendRequest) -> Boolean,
    ): Boolean? =
        try {
            withTimeoutOrNull(timeoutMillis) { send(request) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
}

/** Conditional composer-empty write and its reversible pre-transport restoration. */
internal class ConversationDictationDispatchClaim(
    var emptiedRevision: Long? = null,
    var restoredRevision: Long? = null,
) {
    fun recoveredTarget(target: ConversationDictationTarget): ConversationDictationTarget =
        target.copy(capturedDraftRevision = restoredRevision ?: target.capturedDraftRevision)
}
