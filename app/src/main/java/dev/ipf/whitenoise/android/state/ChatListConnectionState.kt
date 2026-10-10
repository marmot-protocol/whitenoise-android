package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal enum class ChatListConnectionPhase(
    val canAcceptReadiness: Boolean,
) {
    Idle(false),
    Validating(true),
    Attempting(true),
    Ready(false),
}

/**
 * Active-account application connectivity owned by [ChatsController].
 *
 * This deliberately does not contain device-wide relay health. A local
 * subscription snapshot and a relay used by another account cannot prove that
 * this account's application path is ready.
 */
@Immutable
internal data class ChatListConnectionState(
    val accountRef: String? = null,
    val runtimeGeneration: Int = -1,
    val bindEpoch: Long = 0L,
    val sessionAttemptId: Long = 0L,
    val evidenceEpoch: Long = 0L,
    val phase: ChatListConnectionPhase = ChatListConnectionPhase.Idle,
)

internal data class ChatListConnectionEvidenceToken(
    val accountRef: String,
    val runtimeGeneration: Int,
    val bindEpoch: Long,
    val sessionAttemptId: Long,
    val evidenceEpoch: Long,
)

private data class ChatListConnectionSessionIdentity(
    val accountRef: String,
    val runtimeGeneration: Int,
    val bindEpoch: Long,
    val sessionAttemptId: Long,
)

internal fun ChatListConnectionState.beginSessionAttempt(
    accountRef: String,
    runtimeGeneration: Int,
    bindEpoch: Long,
): ChatListConnectionState =
    ChatListConnectionState(
        accountRef = accountRef,
        runtimeGeneration = runtimeGeneration,
        bindEpoch = bindEpoch,
        sessionAttemptId = sessionAttemptId + 1L,
        evidenceEpoch = evidenceEpoch + 1L,
        phase = ChatListConnectionPhase.Attempting,
    )

internal fun ChatListConnectionState.beginSubscriptionValidation(
    accountRef: String,
    runtimeGeneration: Int,
    bindEpoch: Long,
): ChatListConnectionState =
    ChatListConnectionState(
        accountRef = accountRef,
        runtimeGeneration = runtimeGeneration,
        bindEpoch = bindEpoch,
        sessionAttemptId = sessionAttemptId + 1L,
        evidenceEpoch = evidenceEpoch + 1L,
        phase = ChatListConnectionPhase.Validating,
    )

internal fun ChatListConnectionState.beginReadinessRefresh(presentAttempt: Boolean): ChatListConnectionState =
    copy(
        evidenceEpoch = evidenceEpoch + 1L,
        phase =
            if (presentAttempt) {
                ChatListConnectionPhase.Attempting
            } else {
                ChatListConnectionPhase.Validating
            },
    )

internal fun ChatListConnectionState.invalidateReadiness(): ChatListConnectionState =
    copy(
        evidenceEpoch = evidenceEpoch + 1L,
        phase = ChatListConnectionPhase.Idle,
    )

internal fun ChatListConnectionState.evidenceTokenOrNull(): ChatListConnectionEvidenceToken? {
    val currentAccountRef = accountRef ?: return null
    return ChatListConnectionEvidenceToken(
        accountRef = currentAccountRef,
        runtimeGeneration = runtimeGeneration,
        bindEpoch = bindEpoch,
        sessionAttemptId = sessionAttemptId,
        evidenceEpoch = evidenceEpoch,
    )
}

internal fun ChatListConnectionState.readyFromCatchUp(token: ChatListConnectionEvidenceToken): ChatListConnectionState =
    if (matches(token) && phase.canAcceptReadiness) {
        copy(phase = ChatListConnectionPhase.Ready)
    } else {
        this
    }

/** Applies only executed catch-up outcomes; coalesced requests leave readiness unchanged. */
internal fun ChatListConnectionState.applyCatchUpResult(
    token: ChatListConnectionEvidenceToken,
    result: AccountCatchUpResult,
): ChatListConnectionState =
    when (result.outcome) {
        AccountCatchUpOutcome.Succeeded -> readyFromCatchUp(token)
        AccountCatchUpOutcome.Failed -> {
            if (matches(token) && phase.canAcceptReadiness) invalidateReadiness() else this
        }
        AccountCatchUpOutcome.Superseded -> this
    }

internal fun ChatListConnectionState.readyFromLiveUpdate(
    accountRef: String,
    runtimeGeneration: Int,
    bindEpoch: Long,
    sessionAttemptId: Long,
    hasValidatedInternet: Boolean,
): ChatListConnectionState =
    if (
        hasValidatedInternet &&
        sessionIdentityOrNull() ==
        ChatListConnectionSessionIdentity(accountRef, runtimeGeneration, bindEpoch, sessionAttemptId)
    ) {
        copy(phase = ChatListConnectionPhase.Ready)
    } else {
        this
    }

internal fun ChatListConnectionState.finishSessionAttempt(
    accountRef: String,
    runtimeGeneration: Int,
    bindEpoch: Long,
    sessionAttemptId: Long,
): ChatListConnectionState {
    val expected = ChatListConnectionSessionIdentity(accountRef, runtimeGeneration, bindEpoch, sessionAttemptId)
    return if (sessionIdentityOrNull() == expected) {
        copy(
            sessionAttemptId = this.sessionAttemptId + 1L,
            evidenceEpoch = evidenceEpoch + 1L,
            phase = ChatListConnectionPhase.Idle,
        )
    } else {
        this
    }
}

private fun ChatListConnectionState.matches(token: ChatListConnectionEvidenceToken): Boolean =
    accountRef == token.accountRef &&
        runtimeGeneration == token.runtimeGeneration &&
        bindEpoch == token.bindEpoch &&
        sessionAttemptId == token.sessionAttemptId &&
        evidenceEpoch == token.evidenceEpoch

private fun ChatListConnectionState.sessionIdentityOrNull(): ChatListConnectionSessionIdentity? =
    accountRef?.let { ChatListConnectionSessionIdentity(it, runtimeGeneration, bindEpoch, sessionAttemptId) }

/** Owns the controller's observable readiness state and stale-result fences. */
@Suppress("TooManyFunctions") // Readiness transitions and their diagnostics share one fenced owner.
internal class ChatListConnectionOwner(
    private val runtimeGeneration: () -> Int,
    private val hasValidatedInternet: () -> Boolean,
    private val launchCatchUpRequest: () -> Deferred<AccountCatchUpResult>,
    private val hasCurrentSubscriptions: () -> Boolean,
    private val diagnostics: ChatListConnectionDiagnostics? = null,
) {
    constructor(
        appState: WhiteNoiseAppState,
        hasCurrentSubscriptions: () -> Boolean,
    ) : this(
        runtimeGeneration = { appState.runtimeGeneration },
        hasValidatedInternet = { appState.connectivitySignals.value.hasValidatedInternet },
        launchCatchUpRequest = appState::launchCatchUpAccounts,
        hasCurrentSubscriptions = hasCurrentSubscriptions,
        diagnostics = ChatListConnectionDiagnostics(appState::chatListConnectionDiagnosticConfiguration),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var readinessJob: Job? = null
    private var cleared = false

    var state by mutableStateOf(ChatListConnectionState())
        private set

    /** Starts a new bind lifetime and clears readiness inherited from the previous account. */
    fun reset(
        accountRef: String?,
        bindEpoch: Long,
    ) {
        readinessJob?.cancel()
        diagnostics?.clear()
        state =
            ChatListConnectionState(
                accountRef = accountRef,
                runtimeGeneration = runtimeGeneration(),
                bindEpoch = bindEpoch,
            )
    }

    /** Opens a fenced session attempt for the current runtime and bind lifetime. */
    fun beginSessionAttempt(
        accountRef: String,
        bindEpoch: Long,
    ): ChatListConnectionState {
        readinessJob?.cancel()
        state = state.beginSessionAttempt(accountRef, runtimeGeneration(), bindEpoch)
        diagnostics?.begin(PerformancePhase.CONNECTION_SESSION_ATTEMPT, state)
        return state
    }

    /** Moves a current session attempt into subscription validation. */
    fun beginSubscriptionValidation(
        accountRef: String,
        bindEpoch: Long,
    ): ChatListConnectionState {
        readinessJob?.cancel()
        state = state.beginSubscriptionValidation(accountRef, runtimeGeneration(), bindEpoch)
        diagnostics?.begin(PerformancePhase.CONNECTION_SESSION_VALIDATION, state)
        return state
    }

    /** Observes bounded replacement work while the captured readiness evidence remains current. */
    fun observe(catchUp: Deferred<AccountCatchUpResult>) {
        val token = state.evidenceTokenOrNull() ?: return
        if (!state.phase.canAcceptReadiness) return
        readinessJob?.cancel()
        readinessJob =
            scope.launch {
                val result = awaitReadinessCatchUp(catchUp, token)
                val ownsResult = state.matches(token)
                state = state.applyCatchUpResult(token, result)
                if (ownsResult) {
                    diagnostics?.event(
                        when (result.outcome) {
                            AccountCatchUpOutcome.Succeeded -> PerformancePhase.CONNECTION_CATCH_UP_SUCCEEDED
                            AccountCatchUpOutcome.Failed -> PerformancePhase.CONNECTION_CATCH_UP_FAILED
                            AccountCatchUpOutcome.Superseded -> PerformancePhase.CONNECTION_CATCH_UP_SUPERSEDED
                        },
                        state,
                    )
                }
            }
    }

    /** A failed native request settles readiness; cancellation still unwinds the owning observer. */
    private suspend fun awaitReadinessCatchUp(
        catchUp: Deferred<AccountCatchUpResult>,
        token: ChatListConnectionEvidenceToken,
    ): AccountCatchUpResult =
        runCatchingCancellable {
            awaitCatchUpAfterSupersession(
                initial = catchUp.await(),
                launchReplacement = {
                    if (!state.matches(token) || !state.phase.canAcceptReadiness) {
                        AccountCatchUpResult(AccountCatchUpOutcome.Failed)
                    } else {
                        diagnostics?.event(PerformancePhase.CONNECTION_CATCH_UP_SUPERSEDED, state)
                        launchCatchUpRequest().await()
                    }
                },
            )
        }.getOrElse { AccountCatchUpResult(AccountCatchUpOutcome.Failed) }

    /** Publishes live-update readiness only when the captured attempt remains current. */
    fun noteLiveUpdate(attempt: ChatListConnectionState) {
        val account = attempt.accountRef ?: return
        val previousPhase = state.phase
        state =
            state.readyFromLiveUpdate(
                accountRef = account,
                runtimeGeneration = attempt.runtimeGeneration,
                bindEpoch = attempt.bindEpoch,
                sessionAttemptId = attempt.sessionAttemptId,
                hasValidatedInternet = hasValidatedInternet(),
            )
        if (state.phase != previousPhase && state.phase == ChatListConnectionPhase.Ready) {
            diagnostics?.event(PerformancePhase.CONNECTION_READY, state)
        }
    }

    /** Attributes only boundaries belonging to the current account/runtime/subscription attempt. */
    fun noteSubscriptionBoundary(
        attempt: ChatListConnectionState,
        phase: PerformancePhase,
        durationMs: Long = 0L,
    ) {
        if (state.sessionIdentityOrNull() == attempt.sessionIdentityOrNull()) {
            diagnostics?.event(phase, state, durationMs)
        }
    }

    /** Records a rendered status boundary without granting readiness or changing transport policy. */
    fun notePresentation(
        phase: PerformancePhase,
        durationMs: Long = 0L,
    ) {
        diagnostics?.event(phase, state, durationMs)
    }

    /** Completes the captured attempt without allowing stale work to replace newer state. */
    fun finishSessionAttempt(attempt: ChatListConnectionState) {
        val account = attempt.accountRef ?: return
        val finished =
            state.finishSessionAttempt(
                accountRef = account,
                runtimeGeneration = attempt.runtimeGeneration,
                bindEpoch = attempt.bindEpoch,
                sessionAttemptId = attempt.sessionAttemptId,
            )
        if (finished != state) {
            readinessJob?.cancel()
            state = finished
            diagnostics?.event(PerformancePhase.CONNECTION_SESSION_ENDED, state)
        }
    }

    /** Re-evaluates readiness after connectivity, lifecycle, or subscription evidence changes. */
    fun refresh(
        presentAttempt: Boolean,
        source: PerformancePhase = PerformancePhase.CONNECTION_SESSION_VALIDATION,
    ) {
        if (!cleared) {
            when {
                !hasValidatedInternet() -> {
                    invalidate(PerformancePhase.CONNECTION_NETWORK_LOST)
                }
                hasCurrentSubscriptions() && shouldRefresh(presentAttempt) -> {
                    state = state.beginReadinessRefresh(presentAttempt)
                    diagnostics?.begin(
                        if (presentAttempt) PerformancePhase.CONNECTION_NETWORK_RECOVERY else source,
                        state,
                    )
                    observe(launchCatchUp())
                }
                else -> diagnostics?.event(source, state)
            }
        }
    }

    private fun shouldRefresh(presentAttempt: Boolean): Boolean =
        when (state.phase) {
            ChatListConnectionPhase.Attempting -> false
            ChatListConnectionPhase.Validating -> presentAttempt
            ChatListConnectionPhase.Idle,
            ChatListConnectionPhase.Ready,
            -> true
        }

    /** Starts or joins the process-owned catch-up for the current account and network lifetime. */
    fun launchCatchUp(): Deferred<AccountCatchUpResult> = launchCatchUpRequest()

    /** Invalidates current readiness and cancels any result awaiting publication. */
    fun invalidate(source: PerformancePhase = PerformancePhase.CONNECTION_INVALIDATED) {
        readinessJob?.cancel()
        if (source != PerformancePhase.CONNECTION_INVALIDATED) diagnostics?.begin(source, state)
        if (state.phase != ChatListConnectionPhase.Idle) state = state.invalidateReadiness()
        diagnostics?.event(PerformancePhase.CONNECTION_INVALIDATED, state)
    }

    /** Permanently releases this owner and its pending readiness observation. */
    fun clear() {
        cleared = true
        readinessJob?.cancel()
        scope.cancel()
        diagnostics?.clear()
        state = ChatListConnectionState()
    }
}
