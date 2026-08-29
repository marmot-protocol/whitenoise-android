package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal enum class ChatListConnectionPhase {
    Idle,
    Attempting,
    Ready,
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

internal fun ChatListConnectionState.beginReadinessRefresh(): ChatListConnectionState =
    copy(
        evidenceEpoch = evidenceEpoch + 1L,
        phase = ChatListConnectionPhase.Attempting,
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
    if (matches(token) && phase == ChatListConnectionPhase.Attempting) {
        copy(phase = ChatListConnectionPhase.Ready)
    } else {
        this
    }

internal fun ChatListConnectionState.catchUpFailed(token: ChatListConnectionEvidenceToken): ChatListConnectionState =
    if (matches(token) && phase == ChatListConnectionPhase.Attempting) {
        invalidateReadiness()
    } else {
        this
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
internal class ChatListConnectionOwner(
    private val appState: WhiteNoiseAppState,
    private val hasCurrentSubscriptions: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var readinessJob: Job? = null
    private var cleared = false

    var state by mutableStateOf(ChatListConnectionState())
        private set

    fun reset(
        accountRef: String?,
        bindEpoch: Long,
    ) {
        readinessJob?.cancel()
        state =
            ChatListConnectionState(
                accountRef = accountRef,
                runtimeGeneration = appState.runtimeGeneration,
                bindEpoch = bindEpoch,
            )
    }

    fun beginSessionAttempt(
        accountRef: String,
        bindEpoch: Long,
    ): ChatListConnectionState {
        readinessJob?.cancel()
        state = state.beginSessionAttempt(accountRef, appState.runtimeGeneration, bindEpoch)
        return state
    }

    fun observe(catchUp: Deferred<Boolean>) {
        val token = state.evidenceTokenOrNull() ?: return
        if (state.phase != ChatListConnectionPhase.Attempting) return
        readinessJob?.cancel()
        readinessJob =
            scope.launch {
                state =
                    if (catchUp.await()) {
                        state.readyFromCatchUp(token)
                    } else {
                        state.catchUpFailed(token)
                    }
            }
    }

    fun noteLiveUpdate(attempt: ChatListConnectionState) {
        val account = attempt.accountRef ?: return
        state =
            state.readyFromLiveUpdate(
                accountRef = account,
                runtimeGeneration = attempt.runtimeGeneration,
                bindEpoch = attempt.bindEpoch,
                sessionAttemptId = attempt.sessionAttemptId,
                hasValidatedInternet = appState.connectivitySignals.value.hasValidatedInternet,
            )
    }

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
        }
    }

    fun refresh() {
        if (!cleared) {
            when {
                !appState.connectivitySignals.value.hasValidatedInternet -> invalidate()
                hasCurrentSubscriptions() && state.phase != ChatListConnectionPhase.Attempting -> {
                    state = state.beginReadinessRefresh()
                    observe(launchCatchUp())
                }
            }
        }
    }

    fun launchCatchUp(): Deferred<Boolean> = appState.launchCatchUpAccounts(state.evidenceTokenOrNull())

    fun invalidate() {
        readinessJob?.cancel()
        if (state.phase != ChatListConnectionPhase.Idle) state = state.invalidateReadiness()
    }

    fun clear() {
        cleared = true
        readinessJob?.cancel()
        scope.cancel()
        state = ChatListConnectionState()
    }
}
