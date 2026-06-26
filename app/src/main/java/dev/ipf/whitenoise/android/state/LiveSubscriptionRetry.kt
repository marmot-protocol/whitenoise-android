package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/** Initial backoff before reconnecting a live Marmot subscription (matches iOS). */
internal const val LIVE_SUBSCRIPTION_INITIAL_RETRY_DELAY_MS: Long = 500L

/** Maximum backoff between live subscription reconnect attempts (matches iOS). */
internal const val LIVE_SUBSCRIPTION_MAX_RETRY_DELAY_MS: Long = 8_000L

/**
 * Next delay for a live subscription retry loop: double [current], clamped to
 * [LIVE_SUBSCRIPTION_MAX_RETRY_DELAY_MS].
 */
internal fun nextLiveSubscriptionRetryDelayMillis(current: Long): Long = nextRetryBackoffMillis(current, LIVE_SUBSCRIPTION_MAX_RETRY_DELAY_MS)

/**
 * Whether an account-scoped live subscription loop should reconnect after one
 * iteration ends. A destructive account teardown clears the controller's bound
 * account before closing its active handles; the old loop must not immediately
 * reconnect to the account that is being wiped.
 */
internal fun shouldRetryLiveSubscriptionForAccount(
    requestedAccountRef: String,
    currentBoundAccountRef: String?,
): Boolean = currentBoundAccountRef == requestedAccountRef

/**
 * Whether a destructive account teardown owns this controller's active live
 * subscriptions and should drain them before deleting the engine account.
 */
internal fun shouldTeardownLiveSubscriptionsForAccount(
    teardownAccountRef: String,
    controllerAccountRef: String?,
    controllerBoundAccountRef: String?,
): Boolean = controllerAccountRef == teardownAccountRef || controllerBoundAccountRef == teardownAccountRef

/** Whether account teardown should cancel the lifecycle job that owns subscriptions. */
internal fun shouldCancelLiveSubscriptionJob(
    liveSubscriptionJob: Job?,
    currentJob: Job?,
): Boolean = liveSubscriptionJob != null && liveSubscriptionJob !== currentJob

internal data class DestructiveAccountWipeRuntimeState(
    val activeAccountRef: String?,
    val activeConversationAccountRef: String?,
    val activeConversationGroupIdHex: String?,
    val runtimeGeneration: Int,
)

internal fun prepareDestructiveAccountWipeRuntimeState(state: DestructiveAccountWipeRuntimeState): DestructiveAccountWipeRuntimeState =
    state.copy(
        activeAccountRef = null,
        activeConversationAccountRef = null,
        activeConversationGroupIdHex = null,
        runtimeGeneration = state.runtimeGeneration + 1,
    )

internal fun restoreFailedDestructiveAccountWipeRuntimeState(
    state: DestructiveAccountWipeRuntimeState,
    restoredAccountRef: String,
): DestructiveAccountWipeRuntimeState =
    state.copy(
        activeAccountRef = restoredAccountRef,
        // A failed wipe restores the account shell, not the old foreground chat.
        // Reopening the chat creates fresh subscriptions for the restored account.
        activeConversationAccountRef = null,
        activeConversationGroupIdHex = null,
        runtimeGeneration = state.runtimeGeneration + 1,
    )

internal suspend fun <T> Mutex.withSerializedNativePushWipe(block: suspend () -> T): T = withLock { block() }

/**
 * Run two live subscription consumers in parallel until either finishes
 * (normally or with failure). Cancels the sibling, then rethrows the first
 * recorded failure so callers can handle it in their retry loop.
 */
internal suspend fun CoroutineScope.runUntilFirstLiveSubscriptionEnds(
    first: suspend CoroutineScope.() -> Unit,
    second: suspend CoroutineScope.() -> Unit,
) {
    supervisorScope {
        val ended = CompletableDeferred<Unit>()
        val failure = AtomicReference<Throwable?>(null)
        val jobs =
            listOf(
                launch {
                    try {
                        first()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (throwable: Throwable) {
                        failure.compareAndSet(null, throwable)
                    } finally {
                        ended.complete(Unit)
                    }
                },
                launch {
                    try {
                        second()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (throwable: Throwable) {
                        failure.compareAndSet(null, throwable)
                    } finally {
                        ended.complete(Unit)
                    }
                },
            )
        try {
            ended.await()
        } finally {
            jobs.forEach { it.cancel() }
            joinAll(*jobs.toTypedArray())
        }
        failure.get()?.let { throw it }
    }
}
